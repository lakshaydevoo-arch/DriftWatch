package io.driftwatch.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A model registered for monitoring. Mapped to {@code monitored_model} (Flyway V1). */
@Entity
@Table(name = "monitored_model")
public class MonitoredModel {

  /**
   * Time-ordered UUID (version 7): not guessable like a sequence, but inserted in roughly
   * increasing order, which keeps the primary-key index compact.
   */
  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(nullable = false, length = 100, updatable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 32, updatable = false)
  private TaskType taskType;

  @Column(name = "target_column")
  private String targetColumn;

  @Column(name = "prediction_column")
  private String predictionColumn;

  /**
   * Set in Java and truncated to microseconds, PostgreSQL's timestamp precision. Without the
   * truncation, the value returned right after creation (nanoseconds on Linux) would differ from
   * the value read back later.
   */
  /** Identifies a record in batches and labels, so late labels can be joined (V5). */
  @Column(name = "record_id_column", updatable = false)
  private String recordIdColumn;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected MonitoredModel() {
    // for JPA
  }

  public MonitoredModel(
      String name,
      TaskType taskType,
      String targetColumn,
      String predictionColumn,
      String recordIdColumn) {
    this.name = name;
    this.taskType = taskType;
    this.targetColumn = targetColumn;
    this.predictionColumn = predictionColumn;
    this.recordIdColumn = recordIdColumn;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public TaskType getTaskType() {
    return taskType;
  }

  public String getTargetColumn() {
    return targetColumn;
  }

  public String getPredictionColumn() {
    return predictionColumn;
  }

  public String getRecordIdColumn() {
    return recordIdColumn;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
