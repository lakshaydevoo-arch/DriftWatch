package io.driftwatch.backend.run;

import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsProtocolException;
import io.driftwatch.backend.analytics.AnalyticsRejectedException;
import io.driftwatch.backend.analytics.AnalyticsUnavailableException;
import io.driftwatch.backend.analytics.DriftResult;
import io.driftwatch.backend.dataset.Dataset;
import io.driftwatch.backend.dataset.DatasetService;
import io.driftwatch.backend.reference.Reference;
import io.driftwatch.backend.reference.ReferenceService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes one monitoring run on a worker thread.
 *
 * <ol>
 *   <li>Claim the run (PENDING → RUNNING) with a conditional update; if another caller already did,
 *       stop.
 *   <li>Load the reference profile and the current batch, call the engine. No transaction is open
 *       while the engine works (a run can take minutes).
 *   <li>Store the outcome in one short transaction: the complete result (SUCCEEDED) or the reason
 *       (FAILED). Never a partial result.
 * </ol>
 *
 * <p>The engine is called once; a failed run is not retried automatically. Rejections ({@code
 * schema-mismatch}, ...) would fail again, and repeating a 100 MB upload doubles the engine's load.
 * The client can submit the batch again.
 */
@Component
class RunWorker {

  static final String UNAVAILABLE = "analytics-unavailable";
  static final String PROTOCOL_ERROR = "analytics-protocol-error";
  static final String INTERRUPTED = "interrupted";
  static final String INTERNAL_ERROR = "internal-error";

  private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

  private final MonitoringRunRepository runs;
  private final ReferenceService references;
  private final DatasetService datasets;
  private final AnalyticsClient analytics;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  RunWorker(
      MonitoringRunRepository runs,
      ReferenceService references,
      DatasetService datasets,
      AnalyticsClient analytics,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.runs = runs;
    this.references = references;
    this.datasets = datasets;
    this.analytics = analytics;
    this.transactions = transactions;
    this.json = json;
  }

  void execute(UUID runId) {
    Integer claimed = transactions.execute(status -> runs.claim(runId, MonitoringRun.now()));
    if (claimed == null || claimed == 0) {
      log.info("Run {} was not PENDING any more; skipped", runId);
      return;
    }
    log.info("Run {} started", runId);
    try {
      JsonNode response = callEngine(runId);
      String engineVersion = validate(response);
      String result = json.writeValueAsString(response);
      finish(runId, run -> run.succeed(engineVersion, result));
      log.info("Run {} succeeded", runId);
    } catch (AnalyticsRejectedException e) {
      log.info("Run {} rejected by the engine: {} ({})", runId, e.getKind(), e.getDetail());
      String details = e.getErrors().isEmpty() ? null : json.writeValueAsString(e.getErrors());
      failSafely(runId, e.getKind(), e.getDetail(), details);
    } catch (AnalyticsUnavailableException e) {
      if (Thread.currentThread().isInterrupted()) {
        interrupted(runId);
        return;
      }
      // The message names the engine's address: log it, store a generic reason.
      log.warn("Run {} failed, engine unavailable: {}", runId, e.getMessage());
      failSafely(
          runId,
          UNAVAILABLE,
          "The analytics engine was unavailable or timed out; submit the batch again later.",
          null);
    } catch (AnalyticsProtocolException e) {
      log.error("Run {} failed, engine broke the API contract", runId, e);
      failSafely(
          runId, PROTOCOL_ERROR, "The analytics engine returned an unexpected response.", null);
    } catch (RuntimeException e) {
      if (Thread.currentThread().isInterrupted()) {
        interrupted(runId);
        return;
      }
      log.error("Run {} failed unexpectedly", runId, e);
      failSafely(runId, INTERNAL_ERROR, "An unexpected error occurred.", null);
    }
  }

  private JsonNode callEngine(UUID runId) {
    MonitoringRun run = runs.findById(runId).orElseThrow();
    Reference reference = references.get(run.getReferenceId());
    Dataset dataset = datasets.get(run.getCurrentDatasetId());
    Resource current = datasets.resource(dataset);
    JsonNode config =
        run.getRequestedConfig() == null ? null : json.readTree(run.getRequestedConfig());
    String filename =
        dataset.getOriginalFilename() == null ? "current.csv" : dataset.getOriginalFilename();
    DriftResult result =
        analytics.drift(
            reference.getProfile().getBytes(StandardCharsets.UTF_8), current, filename, config);
    return result.body();
  }

  /**
   * Checks the fields the backend relies on, so a SUCCEEDED run always has a usable result.
   *
   * @return the engine version that produced the result
   */
  private static String validate(JsonNode response) {
    if (response == null || !response.isObject()) {
      throw new AnalyticsProtocolException("drift response is not a JSON object");
    }
    JsonNode version = response.get("engine_version");
    if (version == null || !version.isString() || version.asString().isBlank()) {
      throw new AnalyticsProtocolException("drift response has no valid 'engine_version'");
    }
    JsonNode summary = response.get("summary");
    if (summary == null || !summary.isObject()) {
      throw new AnalyticsProtocolException("drift response has no 'summary' object");
    }
    JsonNode features = response.get("features");
    if (features == null || !features.isArray()) {
      throw new AnalyticsProtocolException("drift response has no 'features' array");
    }
    JsonNode rows = response.get("n_rows");
    if (rows == null || !rows.canConvertToLong() || rows.asLong() < 0) {
      throw new AnalyticsProtocolException("drift response has no valid 'n_rows'");
    }
    return version.asString();
  }

  private void interrupted(UUID runId) {
    // Shutting down. Leave the run RUNNING: the database may already be closing, and
    // RunRecovery marks it FAILED ('interrupted') at the next start.
    log.warn("Run {} interrupted by shutdown; it will be marked failed at the next start", runId);
  }

  private void finish(UUID runId, Consumer<MonitoringRun> transition) {
    transactions.executeWithoutResult(status -> {
      MonitoringRun run = runs.findById(runId).orElseThrow();
      transition.accept(run);
    });
  }

  /**
   * Records a failure. If even that fails (e.g. the database is down), the run stays RUNNING until
   * the next start, when {@link RunRecovery} marks it FAILED.
   */
  private void failSafely(UUID runId, String kind, String message, String details) {
    try {
      finish(runId, run -> run.fail(kind, message, details));
    } catch (RuntimeException e) {
      log.error("Run {} could not be marked FAILED ({})", runId, kind, e);
    }
  }
}
