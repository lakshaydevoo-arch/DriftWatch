package io.driftwatch.backend.labels;

import io.driftwatch.backend.dataset.Dataset;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Labels uploaded for a run, as returned by the API.
 *
 * @param join the engine's join report, exactly as returned (snake_case, like other engine
 *     payloads): {@code join_rate}, {@code n_joined} and counts of every kind of gap
 * @param metrics the engine's performance metrics on the joined rows, as returned ({@code null} for
 *     uploads made before metrics existed)
 * @param config the {@code PerformanceConfig} the engine applied ({@code null} likewise)
 */
public record LabelsResponse(
    UUID id,
    UUID runId,
    DatasetView dataset,
    String engineVersion,
    JsonNode join,
    JsonNode metrics,
    JsonNode config,
    Instant createdAt) {

  /** The uploaded labels file. */
  public record DatasetView(UUID id, String sha256, long byteSize, String originalFilename) {}

  static LabelsResponse of(RunLabels labels, Dataset dataset, JsonMapper json) {
    return new LabelsResponse(
        labels.getId(),
        labels.getRunId(),
        new DatasetView(
            dataset.getId(),
            dataset.getSha256(),
            dataset.getByteSize(),
            dataset.getOriginalFilename()),
        labels.getEngineVersion(),
        json.readTree(labels.getJoinReport()),
        labels.getMetrics() == null ? null : json.readTree(labels.getMetrics()),
        labels.getConfig() == null ? null : json.readTree(labels.getConfig()),
        labels.getCreatedAt());
  }
}
