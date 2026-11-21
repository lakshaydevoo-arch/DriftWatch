package io.driftwatch.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  private void insertModel(String name, String taskType) {
    jdbc.update(
        "INSERT INTO monitored_model (id, name, task_type) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        name,
        taskType);
  }

  @Test
  void flywayAppliedTheBaselineMigration() {
    List<Map<String, Object>> history =
        jdbc.queryForList(
            "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

    assertThat(history).hasSize(1);
    assertThat(history.getFirst())
        .containsEntry("version", "1")
        .containsEntry("description", "create monitored model")
        .containsEntry("success", true);
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

  @Test
  void nameLongerThan100CharactersIsRejected() {
    assertThatThrownBy(() -> insertModel("m".repeat(101), "REGRESSION"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("value too long");
  }
}
