package io.driftwatch.backend.run;

import io.driftwatch.backend.common.ProblemTypes;
import io.driftwatch.backend.dataset.Dataset;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A monitoring run as returned by the API: its status, what it compares, and either a result
 * summary ({@code SUCCEEDED}) or an error ({@code FAILED}); both are {@code null} otherwise.
 *
 * <p>Per-feature results are not included; they are served by {@code GET
 * /api/v1/runs/{runId}/features}.
 */
public record RunResponse(
    UUID id,
    UUID modelId,
    UUID referenceId,
    DatasetView currentDataset,
    RunStatus status,
    JsonNode requestedConfig,
    ResultView result,
    ErrorView error,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {

  /** The uploaded batch the run compares. */
  public record DatasetView(UUID id, String sha256, long byteSize, String originalFilename) {}

  /**
   * Run-level outcome of a successful run.
   *
   * @param engineVersion engine that ran the comparison
   * @param profileEngineVersion engine that built the reference profile
   * @param rowCount rows in the current batch
   * @param summary the engine's summary: feature counts, {@code drift_share}, {@code dataset_drift}
   * @param config the complete {@code DriftConfig} applied (defaults filled in)
   * @param issues validation warnings about the batch
   * @param warnings run-level warnings, e.g. an engine version mismatch
   */
  public record ResultView(
      String engineVersion,
      String profileEngineVersion,
      long rowCount,
      JsonNode summary,
      JsonNode config,
      JsonNode issues,
      JsonNode warnings) {}

  /**
   * Why a run failed.
   *
   * @param type problem type, e.g. {@code urn:driftwatch:problem:schema-mismatch}; branch on this
   * @param details structured errors from the engine, if any
   */
  public record ErrorView(URI type, String message, JsonNode details) {}

  static RunResponse of(MonitoringRun run, Dataset dataset, JsonMapper json) {
    return of(RunRow.of(run, dataset), json);
  }

  static RunResponse of(RunRow run, JsonMapper json) {
    return new RunResponse(
        run.id(),
        run.modelId(),
        run.referenceId(),
        new DatasetView(run.datasetId(), run.sha256(), run.byteSize(), run.originalFilename()),
        run.status(),
        readOrNull(json, run.requestedConfig()),
        run.status() == RunStatus.SUCCEEDED ? result(run, json) : null,
        run.status() == RunStatus.FAILED
            ? new ErrorView(
                ProblemTypes.of(run.errorKind()),
                run.errorMessage(),
                readOrNull(json, run.errorDetails()))
            : null,
        run.createdAt(),
        run.startedAt(),
        run.finishedAt());
  }

  private static ResultView result(RunRow run, JsonMapper json) {
    JsonNode result = json.readTree(run.resultSummary());
    JsonNode profileVersion = result.get("profile_engine_version");
    return new ResultView(
        run.engineVersion(),
        profileVersion == null || profileVersion.isNull() ? null : profileVersion.asString(),
        result.path("n_rows").asLong(),
        result.get("summary"),
        result.get("config"),
        result.get("issues"),
        result.get("warnings"));
  }

  private static JsonNode readOrNull(JsonMapper json, String value) {
    return value == null ? null : json.readTree(value);
  }
}
