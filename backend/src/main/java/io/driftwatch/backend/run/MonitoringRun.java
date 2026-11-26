package io.driftwatch.backend.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One comparison of a CURRENT dataset with a reference profile. Mapped to {@code monitoring_run}
 * (Flyway V4), whose check constraints guarantee that every status has exactly its fields.
 *
 * <p>{@code PENDING → RUNNING} is not done here but by a conditional update ({@link
 * MonitoringRunRepository#claim}), so a run can be started at most once. The terminal transitions
 * check the current status, so a finished run is never changed again.
 */
@Entity
@Table(name = "monitoring_run")
public class MonitoringRun {

  /** Longest stored error message (column size). */
  static final int MAX_ERROR_MESSAGE = 2000;

  @Id
  @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
  private UUID id;

  @Column(name = "model_id", nullable = false, updatable = false)
  private UUID modelId;

  @Column(name = "reference_profile_id", nullable = false, updatable = false)
  private UUID referenceId;

  @Column(name = "current_dataset_id", nullable = false, updatable = false)
  private UUID currentDatasetId;

  @Column(name = "current_dataset_role", nullable = false, updatable = false, length = 16)
  private String currentDatasetRole = "CURRENT";

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private RunStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "requested_config", updatable = false)
  private String requestedConfig;

  @Column(name = "engine_version", length = 64)
  private String engineVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column
  private String result;

  @Column(name = "error_kind", length = 64)
  private String errorKind;

  @Column(name = "error_message", length = MAX_ERROR_MESSAGE)
  private String errorMessage;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "error_details")
  private String errorDetails;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  protected MonitoringRun() {
    // for JPA
  }

  MonitoringRun(UUID modelId, UUID referenceId, UUID currentDatasetId, String requestedConfig) {
    this.modelId = modelId;
    this.referenceId = referenceId;
    this.currentDatasetId = currentDatasetId;
    this.requestedConfig = requestedConfig;
    this.status = RunStatus.PENDING;
    this.createdAt = now();
  }

  /** RUNNING → SUCCEEDED with the engine's complete response. */
  void succeed(String engineVersion, String result) {
    requireStatus(RunStatus.RUNNING);
    this.status = RunStatus.SUCCEEDED;
    this.engineVersion = engineVersion;
    this.result = result;
    this.finishedAt = later(now());
  }

  /**
   * PENDING or RUNNING → FAILED.
   *
   * @param kind a problem kind, e.g. {@code schema-mismatch}
   * @param details JSON array of structured errors, or {@code null}
   */
  void fail(String kind, String message, String details) {
    if (status.isTerminal()) {
      throw new IllegalStateException("run " + id + " is already " + status);
    }
    this.status = RunStatus.FAILED;
    this.errorKind = kind;
    this.errorMessage = truncate(message);
    this.errorDetails = details;
    this.finishedAt = later(now());
  }

  private void requireStatus(RunStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("run " + id + " is " + status + ", expected " + expected);
    }
  }

  /** Never before the previous timestamp, even if the clock steps back (V4 checks the order). */
  private Instant later(Instant candidate) {
    Instant previous = startedAt != null ? startedAt : createdAt;
    return candidate.isBefore(previous) ? previous : candidate;
  }

  /** Microsecond precision, matching PostgreSQL (see MonitoredModel). */
  static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  static String truncate(String message) {
    if (message == null || message.isBlank()) {
      return "No details were given.";
    }
    return message.length() <= MAX_ERROR_MESSAGE
        ? message
        : message.substring(0, MAX_ERROR_MESSAGE - 1) + "…";
  }

  public UUID getId() {
    return id;
  }

  public UUID getModelId() {
    return modelId;
  }

  public UUID getReferenceId() {
    return referenceId;
  }

  public UUID getCurrentDatasetId() {
    return currentDatasetId;
  }

  public RunStatus getStatus() {
    return status;
  }

  /** The {@code DriftConfig} JSON the client asked for, or {@code null} for engine defaults. */
  public String getRequestedConfig() {
    return requestedConfig;
  }

  public String getEngineVersion() {
    return engineVersion;
  }

  /** The engine's full drift response (only when SUCCEEDED). */
  public String getResult() {
    return result;
  }

  public String getErrorKind() {
    return errorKind;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  /** JSON array of structured errors, or {@code null}. */
  public String getErrorDetails() {
    return errorDetails;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }
}
