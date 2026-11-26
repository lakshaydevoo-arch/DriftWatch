package io.driftwatch.backend.run;

import io.driftwatch.backend.dataset.Dataset;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a run and its dataset, as the API shows them. {@code resultSummary} is the engine's
 * result <em>without</em> the per-feature list, which can be large and has its own endpoint.
 */
record RunRow(
    UUID id,
    UUID modelId,
    UUID referenceId,
    UUID datasetId,
    String sha256,
    long byteSize,
    String originalFilename,
    RunStatus status,
    String requestedConfig,
    String engineVersion,
    String resultSummary,
    String errorKind,
    String errorMessage,
    String errorDetails,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {

  /** From the entity; used right after submission, when the run is certainly PENDING. */
  static RunRow of(MonitoringRun run, Dataset dataset) {
    return new RunRow(
        run.getId(),
        run.getModelId(),
        run.getReferenceId(),
        dataset.getId(),
        dataset.getSha256(),
        dataset.getByteSize(),
        dataset.getOriginalFilename(),
        run.getStatus(),
        run.getRequestedConfig(),
        run.getEngineVersion(),
        run.getResult(),
        run.getErrorKind(),
        run.getErrorMessage(),
        run.getErrorDetails(),
        run.getCreatedAt(),
        run.getStartedAt(),
        run.getFinishedAt());
  }
}
