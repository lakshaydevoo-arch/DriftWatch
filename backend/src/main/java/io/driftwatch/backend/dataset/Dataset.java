package io.driftwatch.backend.dataset;

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

/**
 * One uploaded file. Mapped to {@code dataset} (Flyway V2). Immutable once stored.
 *
 * <p>The model is referenced by id rather than a JPA association: the dataset never needs the
 * model's fields, and the database foreign key already guarantees the model exists.
 */
@Entity
@Table(name = "dataset")
public class Dataset {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "model_id", nullable = false, updatable = false)
  private UUID modelId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16, updatable = false)
  private DatasetRole role;

  @Column(nullable = false, length = 64, updatable = false)
  private String sha256;

  @Column(name = "byte_size", nullable = false, updatable = false)
  private long byteSize;

  @Column(name = "original_filename", updatable = false)
  private String originalFilename;

  /** Microsecond precision, matching PostgreSQL (see MonitoredModel). */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Dataset() {
    // for JPA
  }

  Dataset(UUID modelId, DatasetRole role, String sha256, long byteSize, String originalFilename) {
    this.modelId = modelId;
    this.role = role;
    this.sha256 = sha256;
    this.byteSize = byteSize;
    this.originalFilename = originalFilename;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  public UUID getId() {
    return id;
  }

  public UUID getModelId() {
    return modelId;
  }

  public DatasetRole getRole() {
    return role;
  }

  public String getSha256() {
    return sha256;
  }

  public long getByteSize() {
    return byteSize;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
