package io.driftwatch.backend.run;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The V4 constraints reject runs whose fields do not match their status or whose datasets and
 * references belong elsewhere. Each test runs in a transaction that is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class MonitoringRunSchemaTest {

  @Autowired private JdbcTemplate jdbc;

  private UUID model;
  private UUID reference;

  @BeforeEach
  void setUp() {
    TestDatabase.clear(jdbc);
    model = insertModel("m");
    reference = insertReference(model);
  }

  private UUID insertModel(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO monitored_model (id, name, task_type) VALUES (?, ?, 'REGRESSION')", id, name);
    return id;
  }

  private UUID insertDataset(UUID modelId, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO dataset (id, model_id, role, sha256, byte_size) VALUES (?, ?, ?, ?, 1)",
        id,
        modelId,
        role,
        "c".repeat(64));
    return id;
  }

  private UUID insertReference(UUID modelId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO reference_profile (id, model_id, dataset_id, engine_version, row_count, profile)
        VALUES (?, ?, ?, '0.1.0', 1, '{}'::jsonb)
        """,
        id,
        modelId,
        insertDataset(modelId, "REFERENCE"));
    return id;
  }

  /**
   * Inserts a run. {@code values} is SQL for {@code started_at, finished_at, result,
   * engine_version, error_kind, error_message}.
   */
  private void insertRun(
      UUID modelId, UUID referenceId, UUID datasetId, String status, String values) {
    jdbc.update(
        "INSERT INTO monitoring_run (id, model_id, reference_profile_id, current_dataset_id,"
            + " status, started_at, finished_at, result, engine_version, error_kind,"
            + " error_message) VALUES (?, ?, ?, ?, ?, "
            + values
            + ")",
        UUID.randomUUID(),
        modelId,
        referenceId,
        datasetId,
        status);
  }

  private static final String NOTHING = "NULL, NULL, NULL, NULL, NULL, NULL";
  private static final String STARTED = "now(), NULL, NULL, NULL, NULL, NULL";
  private static final String SUCCEEDED = "now(), now(), '{}'::jsonb, '0.1.0', NULL, NULL";
  private static final String FAILED = "NULL, now(), NULL, NULL, 'internal-error', 'x'";

  private UUID current() {
    return insertDataset(model, "CURRENT");
  }

  @Test
  void eachStatusAcceptsItsOwnFields() {
    assertThatCode(
            () -> {
              insertRun(model, reference, current(), "PENDING", NOTHING);
              insertRun(model, reference, current(), "RUNNING", STARTED);
              insertRun(model, reference, current(), "SUCCEEDED", SUCCEEDED);
              insertRun(model, reference, current(), "FAILED", FAILED);
            })
        .doesNotThrowAnyException();
  }

  @Test
  void aSucceededRunMustHaveAResult() {
    assertThatThrownBy(() ->
            insertRun(
                model,
                reference,
                current(),
                "SUCCEEDED",
                "now(), now(), NULL, '0.1.0', NULL, NULL"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitoring_run_state");
  }

  @Test
  void aFailedRunMustHaveAReason() {
    assertThatThrownBy(() ->
            insertRun(model, reference, current(), "FAILED", "NULL, now(), NULL, NULL, NULL, NULL"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitoring_run_state");
  }

  @Test
  void aFailedRunCannotAlsoHaveAResult() {
    assertThatThrownBy(() ->
            insertRun(
                model,
                reference,
                current(),
                "FAILED",
                "NULL, now(), '{}'::jsonb, NULL, 'internal-error', 'x'"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitoring_run_state");
  }

  @Test
  void aPendingRunCannotHaveStarted() {
    assertThatThrownBy(() -> insertRun(model, reference, current(), "PENDING", STARTED))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitoring_run_state");
  }

  @Test
  void unknownStatusIsRejected() {
    assertThatThrownBy(() -> insertRun(model, reference, current(), "CANCELLED", NOTHING))
        .isInstanceOf(DataIntegrityViolationException.class)
        // ck_monitoring_run_status and ck_monitoring_run_state both reject it; PostgreSQL
        // reports whichever it checks first.
        .hasMessageContaining("violates check constraint");
  }

  @Test
  void theComparedDatasetMustBeACurrentDataset() {
    UUID referenceDataset = insertDataset(model, "REFERENCE");

    assertThatThrownBy(() -> insertRun(model, reference, referenceDataset, "PENDING", NOTHING))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_monitoring_run_dataset");
  }

  @Test
  void theComparedDatasetMustBelongToTheSameModel() {
    UUID otherDataset = insertDataset(insertModel("other"), "CURRENT");

    assertThatThrownBy(() -> insertRun(model, reference, otherDataset, "PENDING", NOTHING))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_monitoring_run_dataset");
  }

  @Test
  void theReferenceMustBelongToTheSameModel() {
    UUID otherReference = insertReference(insertModel("other"));

    assertThatThrownBy(() -> insertRun(model, otherReference, current(), "PENDING", NOTHING))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_monitoring_run_reference");
  }

  @Test
  void aDatasetBelongsToOneRunOnly() {
    UUID dataset = current();
    insertRun(model, reference, dataset, "PENDING", NOTHING);

    assertThatThrownBy(() -> insertRun(model, reference, dataset, "PENDING", NOTHING))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_monitoring_run_current_dataset");
  }
}
