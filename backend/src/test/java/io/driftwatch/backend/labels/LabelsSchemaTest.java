package io.driftwatch.backend.labels;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftwatch.backend.TestDatabase;
import io.driftwatch.backend.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The V5 constraints. Each test runs in a transaction that is rolled back. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LabelsSchemaTest {

  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    TestDatabase.clear(jdbc);
  }

  private UUID model(String name, String target, String prediction, String recordId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO monitored_model (id, name, task_type, target_column, prediction_column,"
            + " record_id_column) VALUES (?, ?, 'REGRESSION', ?, ?, ?)",
        id,
        name,
        target,
        prediction,
        recordId);
    return id;
  }

  private UUID dataset(UUID model, String role) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO dataset (id, model_id, role, sha256, byte_size) VALUES (?, ?, ?, ?, 1)",
        id,
        model,
        role,
        "d".repeat(64));
    return id;
  }

  /** A model with a reference and one PENDING run; returns the run id. */
  private UUID run(UUID model) {
    UUID reference = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO reference_profile (id, model_id, dataset_id, engine_version, row_count,"
            + " profile) VALUES (?, ?, ?, '0.1.0', 1, '{}'::jsonb)",
        reference,
        model,
        dataset(model, "REFERENCE"));
    UUID run = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO monitoring_run (id, model_id, reference_profile_id, current_dataset_id,"
            + " status) VALUES (?, ?, ?, ?, 'PENDING')",
        run,
        model,
        reference,
        dataset(model, "CURRENT"));
    return run;
  }

  private void labels(UUID run, UUID model, UUID dataset) {
    jdbc.update(
        "INSERT INTO run_labels (id, run_id, model_id, dataset_id, engine_version, join_report)"
            + " VALUES (?, ?, ?, ?, '0.1.0', '{}'::jsonb)",
        UUID.randomUUID(),
        run,
        model,
        dataset);
  }

  @Test
  void labelsOfARunAreAccepted() {
    UUID model = model("m", "y", "p", "id");
    UUID run = run(model);

    assertThatCode(() -> labels(run, model, dataset(model, "LABELS"))).doesNotThrowAnyException();
  }

  @Test
  void theLabelsFileMustBeALabelsDataset() {
    UUID model = model("m", "y", "p", "id");
    UUID run = run(model);

    assertThatThrownBy(() -> labels(run, model, dataset(model, "CURRENT")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_run_labels_dataset");
  }

  @Test
  void labelsCannotPointAtAnotherModelsRun() {
    UUID model = model("m", "y", "p", "id");
    UUID other = model("other", "y", "p", "id");
    UUID otherRun = run(other);

    assertThatThrownBy(() -> labels(otherRun, model, dataset(model, "LABELS")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_run_labels_run");
  }

  /** One violation per test: PostgreSQL aborts the transaction after the first error. */
  @ParameterizedTest
  @ValueSource(strings = {"y", "p"})
  void theRecordIdMustDifferFromTheTargetAndPrediction(String recordId) {
    assertThatThrownBy(() -> model("m", "y", "p", recordId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitored_model_distinct_record_id");
  }

  @Test
  void theTargetMustDifferFromThePrediction() {
    assertThatThrownBy(() -> model("m", "y", "y", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitored_model_distinct_columns");
  }

  @Test
  void rolesMayBeAbsent() {
    assertThatCode(() -> model("m", null, null, null)).doesNotThrowAnyException();
  }

  @Test
  void metricsAndTheirConfigAreStoredTogether() {
    UUID model = model("m", "y", "p", "id");
    UUID run = run(model);

    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO run_labels (id, run_id, model_id, dataset_id, engine_version,"
                    + " join_report, metrics) VALUES (?, ?, ?, ?, '0.1.0', '{}'::jsonb,"
                    + " '{}'::jsonb)",
                UUID.randomUUID(),
                run,
                model,
                dataset(model, "LABELS")))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_run_labels_metrics_with_config");
  }
}
