package io.driftwatch.backend.model;

import java.time.Instant;
import java.util.UUID;

/** A registered model as returned by the API. */
public record ModelResponse(
    UUID id,
    String name,
    TaskType taskType,
    String targetColumn,
    String predictionColumn,
    String recordIdColumn,
    Instant createdAt) {

  static ModelResponse from(MonitoredModel model) {
    return new ModelResponse(
        model.getId(),
        model.getName(),
        model.getTaskType(),
        model.getTargetColumn(),
        model.getPredictionColumn(),
        model.getRecordIdColumn(),
        model.getCreatedAt());
  }
}
