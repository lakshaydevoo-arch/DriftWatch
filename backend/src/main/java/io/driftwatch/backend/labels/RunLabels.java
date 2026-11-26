package io.driftwatch.backend.labels;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Ground-truth labels uploaded for a monitoring run, with the engine's join report. Mapped to
 * {@code run_labels} (Flyway V5). Immutable; a newer upload for the same run becomes current.
 */
@Entity
@Table(name = "run_labels")
public class RunLabels {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "run_id", nullable = false, updatable = false)
  private UUID runId;

  @Column(name = "model_id", nullable = false, updatable = false)
  private UUID modelId;

  @Column(name = "dataset_id", nullable = false, updatable = false)
  private UUID datasetId;

  @Column(name = "dataset_role", nullable = false, updatable = false, length = 16)
  private String datasetRole = "LABELS";

  @Column(name = "engine_version", nullable = false, updatable = false, length = 64)
  private String engineVersion;

  /** The engine's join report, as returned. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "join_report", nullable = false, updatable = false)
  private String joinReport;

  /** The {@code PerformanceConfig} the engine applied ({@code null} before V6). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(updatable = false)
  private String config;

  /** The engine's metrics on the joined rows ({@code null} before V6). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(updatable = false)
  private String metrics;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RunLabels() {
    // for JPA
  }

  RunLabels(
      UUID runId,
      UUID modelId,
      UUID datasetId,
      String engineVersion,
      String joinReport,
      String config,
      String metrics) {
    this.runId = runId;
    this.modelId = modelId;
    this.datasetId = datasetId;
    this.engineVersion = engineVersion;
    this.joinReport = joinReport;
    this.config = config;
    this.metrics = metrics;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getRunId() {
    return runId;
  }

  public UUID getModelId() {
    return modelId;
  }

  public UUID getDatasetId() {
    return datasetId;
  }

  public String getEngineVersion() {
    return engineVersion;
  }

  public String getJoinReport() {
    return joinReport;
  }

  public String getConfig() {
    return config;
  }

  public String getMetrics() {
    return metrics;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
