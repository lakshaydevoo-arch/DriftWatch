package io.driftwatch.backend.reference;

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
 * A model's registered reference: a REFERENCE dataset and the engine's profile of it. Mapped to
 * {@code reference_profile} (Flyway V3). Immutable.
 *
 * <p>The profile and issues are kept as JSON text exactly as the engine returned them: drift runs
 * send the profile back unchanged, and the backend does not re-model the engine's data.
 */
@Entity
@Table(name = "reference_profile")
public class Reference {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "model_id", nullable = false, updatable = false)
  private UUID modelId;

  @Column(name = "dataset_id", nullable = false, updatable = false)
  private UUID datasetId;

  @Column(name = "dataset_role", nullable = false, updatable = false, length = 16)
  private String datasetRole = "REFERENCE";

  @Column(name = "engine_version", nullable = false, updatable = false, length = 64)
  private String engineVersion;

  @Column(name = "row_count", nullable = false, updatable = false)
  private long rowCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, updatable = false)
  private String profile;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, updatable = false)
  private String issues;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Reference() {
    // for JPA
  }

  Reference(
      UUID modelId,
      UUID datasetId,
      String engineVersion,
      long rowCount,
      String profile,
      String issues) {
    this.modelId = modelId;
    this.datasetId = datasetId;
    this.engineVersion = engineVersion;
    this.rowCount = rowCount;
    this.profile = profile;
    this.issues = issues;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
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

  public long getRowCount() {
    return rowCount;
  }

  /** The engine's profile JSON, as stored. */
  public String getProfile() {
    return profile;
  }

  public String getIssues() {
    return issues;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
