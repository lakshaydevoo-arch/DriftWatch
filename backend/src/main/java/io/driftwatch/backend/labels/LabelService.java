package io.driftwatch.backend.labels;

import io.driftwatch.backend.analytics.AnalyticsClient;
import io.driftwatch.backend.analytics.AnalyticsProtocolException;
import io.driftwatch.backend.analytics.PerformanceResult;
import io.driftwatch.backend.dataset.Dataset;
import io.driftwatch.backend.dataset.DatasetRole;
import io.driftwatch.backend.dataset.DatasetService;
import io.driftwatch.backend.reference.Reference;
import io.driftwatch.backend.reference.ReferenceService;
import io.driftwatch.backend.run.MonitoringRun;
import io.driftwatch.backend.run.RunService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uploads ground-truth labels for a monitoring run and records how well they join.
 *
 * <p>The engine also computes performance metrics on the joined rows; they are stored with the join
 * report. An optional {@code PerformanceConfig} (e.g. {@code positive_label}) is passed to the
 * engine unchanged; the configuration it applied is stored too.
 *
 * <p>Same shape as reference registration: store the file with no transaction open, have the engine
 * join it (synchronously: a labels file is small and the join is fast), then insert the dataset and
 * the join report in one transaction, only if the engine succeeded. The join uses the run's own
 * batch and the reference the run was pinned to.
 *
 * <p>Labels can be uploaded for a run in any status: the join needs only the batch, which every run
 * has. Uploading again keeps the earlier upload; the newest is current.
 */
@Service
public class LabelService {

  /** Fields of the join report the backend relies on. */
  private static final List<String> REQUIRED_COUNTS =
      List.of("n_current_rows", "n_label_rows", "n_matched", "n_joined");

  private final RunService runs;
  private final ReferenceService references;
  private final DatasetService datasets;
  private final AnalyticsClient analytics;
  private final RunLabelsRepository labels;
  private final TransactionTemplate transactions;
  private final JsonMapper json;

  LabelService(
      RunService runs,
      ReferenceService references,
      DatasetService datasets,
      AnalyticsClient analytics,
      RunLabelsRepository labels,
      TransactionTemplate transactions,
      JsonMapper json) {
    this.runs = runs;
    this.references = references;
    this.datasets = datasets;
    this.analytics = analytics;
    this.labels = labels;
    this.transactions = transactions;
    this.json = json;
  }

  /** An upload together with its dataset. */
  public record Uploaded(RunLabels labels, Dataset dataset) {}

  /**
   * Uploads labels for a run.
   *
   * @throws io.driftwatch.backend.run.RunNotFoundException if the run does not exist
   * @throws io.driftwatch.backend.analytics.AnalyticsException if the join fails (e.g. {@code
   *     labels-not-configured}, {@code duplicate-record-ids})
   */
  public Uploaded upload(UUID runId, String filename, InputStream content, JsonNode config)
      throws IOException {
    MonitoringRun run = runs.get(runId);
    Reference reference = references.get(run.getReferenceId());
    Dataset batch = runs.currentDatasetOf(run);
    DatasetService.StoredUpload upload = datasets.storeFile(run.getModelId(), filename, content);
    PerformanceResult result =
        analytics.performance(
            reference.getProfile().getBytes(StandardCharsets.UTF_8),
            datasets.resource(batch),
            batch.getOriginalFilename() == null ? "current.csv" : batch.getOriginalFilename(),
            datasets.resource(upload),
            filename == null ? "labels.csv" : filename,
            config);
    String engineVersion = validate(result);
    String report = json.writeValueAsString(result.join());
    String appliedConfig = json.writeValueAsString(result.config());
    String metrics = json.writeValueAsString(result.metrics());
    return transactions.execute(status -> {
      Dataset dataset = datasets.record(upload, DatasetRole.LABELS);
      RunLabels saved =
          labels.saveAndFlush(
              new RunLabels(
                  runId,
                  run.getModelId(),
                  dataset.getId(),
                  engineVersion,
                  report,
                  appliedConfig,
                  metrics));
      return new Uploaded(saved, dataset);
    });
  }

  /**
   * The run's current (newest) labels.
   *
   * @throws io.driftwatch.backend.run.RunNotFoundException if the run does not exist
   * @throws LabelsNotFoundException if none were uploaded
   */
  @Transactional(readOnly = true)
  public RunLabels current(UUID runId) {
    runs.get(runId);
    return labels
        .findFirstByRunIdOrderByCreatedAtDescIdDesc(runId)
        .orElseThrow(() -> new LabelsNotFoundException(runId));
  }

  /** The uploaded file behind labels. */
  @Transactional(readOnly = true)
  public Dataset datasetOf(RunLabels uploaded) {
    return datasets.get(uploaded.getDatasetId());
  }

  private static String validate(PerformanceResult result) {
    if (result.engineVersion() == null || result.engineVersion().isBlank()) {
      throw new AnalyticsProtocolException("performance response has no valid 'engine_version'");
    }
    JsonNode join = result.join();
    if (!join.isObject()) {
      throw new AnalyticsProtocolException("performance response 'join' is not an object");
    }
    for (String field : REQUIRED_COUNTS) {
      JsonNode value = join.get(field);
      if (value == null || !value.canConvertToLong() || value.asLong() < 0) {
        throw new AnalyticsProtocolException("join report has no valid '" + field + "'");
      }
    }
    JsonNode metrics = result.metrics();
    JsonNode status = metrics.get("status");
    if (!metrics.isObject() || status == null || !status.isString() || !metrics.has("metrics")) {
      throw new AnalyticsProtocolException("performance response has no valid 'metrics'");
    }
    if (!result.config().isObject()) {
      throw new AnalyticsProtocolException("performance response 'config' is not an object");
    }
    return result.engineVersion();
  }
}
