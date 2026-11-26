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
 * <p>Per-feature results are not included here (they come with the run results API, task 2.8).
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
    return new RunResponse(
        run.getId(),
        run.getModelId(),
        run.getReferenceId(),
        new DatasetView(
            dataset.getId(),
            dataset.getSha256(),
            dataset.getByteSize(),
            dataset.getOriginalFilename()),
        run.getStatus(),
        readOrNull(json, run.getRequestedConfig()),
        run.getStatus() == RunStatus.SUCCEEDED ? result(run, json) : null,
        run.getStatus() == RunStatus.FAILED
            ? new ErrorView(
                ProblemTypes.of(run.getErrorKind()),
                run.getErrorMessage(),
                readOrNull(json, run.getErrorDetails()))
            : null,
        run.getCreatedAt(),
        run.getStartedAt(),
        run.getFinishedAt());
  }

  private static ResultView result(MonitoringRun run, JsonMapper json) {
    JsonNode result = json.readTree(run.getResult());
    JsonNode profileVersion = result.get("profile_engine_version");
    return new ResultView(
        run.getEngineVersion(),
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
