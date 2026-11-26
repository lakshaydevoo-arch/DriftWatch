package io.driftwatch.backend.reference;

import io.driftwatch.backend.dataset.Dataset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A registered reference as returned by the API: the dataset, what the engine inferred about each
 * column, and any validation issues. The full engine profile is not returned (it can be several
 * MB); it is kept for drift runs.
 */
public record ReferenceResponse(
    UUID id,
    UUID modelId,
    DatasetView dataset,
    long rowCount,
    String engineVersion,
    List<ColumnView> columns,
    JsonNode issues,
    Instant createdAt) {

  /** The uploaded file behind the reference. */
  public record DatasetView(UUID id, String sha256, long byteSize, String originalFilename) {}

  /** One column of the inferred schema. */
  public record ColumnView(
      String name, String logicalType, String role, boolean monitored, String excludedReason) {}

  /** The API view of a registered reference. */
  public static ReferenceResponse of(Reference reference, Dataset dataset, JsonMapper json) {
    JsonNode profile = json.readTree(reference.getProfile());
    List<ColumnView> columns = new ArrayList<>();
    for (JsonNode column : profile.path("dataset_schema").path("columns")) {
      JsonNode excluded = column.get("excluded");
      boolean monitored = excluded == null || excluded.isNull();
      columns.add(
          new ColumnView(
              column.path("name").asString(),
              column.path("logical_type").asString(),
              // Profiles stored before roles existed have none: they are all features.
              column.path("role").asString("feature"),
              monitored,
              monitored ? null : excluded.asString()));
    }
    return new ReferenceResponse(
        reference.getId(),
        reference.getModelId(),
        new DatasetView(
            dataset.getId(),
            dataset.getSha256(),
            dataset.getByteSize(),
            dataset.getOriginalFilename()),
        reference.getRowCount(),
        reference.getEngineVersion(),
        List.copyOf(columns),
        json.readTree(reference.getIssues()),
        reference.getCreatedAt());
  }
}
