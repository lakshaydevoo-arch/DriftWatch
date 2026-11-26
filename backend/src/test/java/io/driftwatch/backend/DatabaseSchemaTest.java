package io.driftwatch.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks the Flyway-managed schema on a real PostgreSQL 16, including that each constraint rejects
 * the data it is meant to reject. Each test runs in a transaction that is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DatabaseSchemaTest {

  @Autowired private JdbcTemplate jdbc;

  /**
   * Other test classes share this database (same cached Spring context). Start from empty tables;
   * this runs inside the test transaction, so it is rolled back too.
   */
  @BeforeEach
  void emptyTables() {
    TestDatabase.clear(jdbc);
  }

  private void insertModel(String name, String taskType) {
    jdbc.update(
        "INSERT INTO monitored_model (id, name, task_type) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        name,
        taskType);
  }

  @Test
  void flywayAppliedAllMigrationsInOrder() {
    List<Map<String, Object>> history =
        jdbc.queryForList(
            "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

    assertThat(history)
        .extracting(h -> h.get("version"), h -> h.get("description"), h -> h.get("success"))
        .containsExactly(
            tuple("1", "create monitored model", true),
            tuple("2", "create dataset", true),
            tuple("3", "create reference profile", true),
            tuple("4", "create monitoring run", true));
  }

  @Test
  void databaseIsPostgres16() {
    String version = jdbc.queryForObject("SHOW server_version", String.class);

    assertThat(version).startsWith("16.");
  }

  @Test
  void monitoredModelHasTheExpectedColumns() {
    List<Map<String, Object>> columns =
        jdbc.queryForList(
            """
            SELECT column_name, data_type, is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = 'monitored_model'
            ORDER BY ordinal_position
            """);

    assertThat(columns)
        .extracting(c -> c.get("column_name"), c -> c.get("data_type"), c -> c.get("is_nullable"))
        .containsExactly(
            tuple("id", "uuid", "NO"),
            tuple("name", "character varying", "NO"),
            tuple("task_type", "character varying", "NO"),
            tuple("target_column", "character varying", "YES"),
            tuple("prediction_column", "character varying", "YES"),
            tuple("created_at", "timestamp with time zone", "NO"));
  }

  @Test
  void validRowIsStoredWithCreationTimestamp() {
    insertModel("credit-risk", "BINARY_CLASSIFICATION");

    OffsetDateTime createdAt =
        jdbc.queryForObject(
            "SELECT created_at FROM monitored_model WHERE name = 'credit-risk'",
            OffsetDateTime.class);

    assertThat(createdAt).isNotNull();
  }

  @Test
  void duplicateNamesAreRejected() {
    insertModel("credit-risk", "BINARY_CLASSIFICATION");

    assertThatThrownBy(() -> insertModel("credit-risk", "REGRESSION"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uq_monitored_model_name");
  }

  @Test
  void unknownTaskTypeIsRejected() {
    assertThatThrownBy(() -> insertModel("m", "CLUSTERING"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitored_model_task_type");
  }

  @Test
  void blankNameIsRejected() {
    assertThatThrownBy(() -> insertModel("   ", "REGRESSION"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_monitored_model_name_not_blank");
  }

  @Test
  void missingNameIsRejected() {
    assertThatThrownBy(() -> insertModel(null, "REGRESSION"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("null value in column \"name\"");
  }

  private UUID insertModelReturningId(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO monitored_model (id, name, task_type) VALUES (?, ?, 'REGRESSION')", id, name);
    return id;
  }

  private void insertDataset(UUID modelId, String role, String sha256, long size) {
    jdbc.update(
        "INSERT INTO dataset (id, model_id, role, sha256, byte_size) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        modelId,
        role,
        sha256,
        size);
  }

  @Test
  void datasetRequiresAnExistingModel() {
    assertThatThrownBy(() -> insertDataset(UUID.randomUUID(), "REFERENCE", "a".repeat(64), 10))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("dataset_model_id_fkey");
  }

  @Test
  void modelWithDatasetsCannotBeDeleted() {
    UUID model = insertModelReturningId("m");
    insertDataset(model, "REFERENCE", "a".repeat(64), 10);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM monitored_model WHERE id = ?", model))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("dataset_model_id_fkey");
  }

  @Test
  void datasetRoleIsRestricted() {
    UUID model = insertModelReturningId("m");

    assertThatThrownBy(() -> insertDataset(model, "TRAINING", "a".repeat(64), 10))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_dataset_role");
  }

  @Test
  void datasetHashMustBeLowerCaseHex() {
    UUID model = insertModelReturningId("m");

    assertThatThrownBy(() -> insertDataset(model, "REFERENCE", "A".repeat(64), 10))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_dataset_sha256_format");
  }

  @Test
  void datasetSizeMustBePositive() {
    UUID model = insertModelReturningId("m");

    assertThatThrownBy(() -> insertDataset(model, "REFERENCE", "a".repeat(64), 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_dataset_byte_size_positive");
  }

  @Test
  void nameLongerThan100CharactersIsRejected() {
    assertThatThrownBy(() -> insertModel("m".repeat(101), "REGRESSION"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("value too long");
  }
}
