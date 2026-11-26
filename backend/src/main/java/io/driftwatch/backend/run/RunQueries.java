package io.driftwatch.backend.run;

import io.driftwatch.backend.model.ModelService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries behind the runs API, in SQL so that PostgreSQL does the JSON work: run views
 * drop the per-feature list ({@code result - 'features'}) and the features endpoint reads only
 * {@code result -> 'features'}. A page of run history therefore never loads every feature of every
 * run.
 */
@Component
class RunQueries {

  /** Features of one run, or why there are none. */
  record Features(RunStatus status, String features) {}

  private static final String RUN_COLUMNS =
      """
      SELECT r.id, r.model_id, r.reference_profile_id, r.current_dataset_id,
             d.sha256, d.byte_size, d.original_filename,
             r.status, r.requested_config::text AS requested_config, r.engine_version,
             (r.result - 'features')::text AS result_summary,
             r.error_kind, r.error_message, r.error_details::text AS error_details,
             r.created_at, r.started_at, r.finished_at
        FROM monitoring_run r
        JOIN dataset d ON d.id = r.current_dataset_id
      """;

  private static final RowMapper<RunRow> ROW = RunQueries::mapRow;

  private final JdbcTemplate jdbc;
  private final ModelService models;

  RunQueries(JdbcTemplate jdbc, ModelService models) {
    this.jdbc = jdbc;
    this.models = models;
  }

  /**
   * One run.
   *
   * @throws RunNotFoundException if there is no such run
   */
  @Transactional(readOnly = true)
  public RunRow run(UUID runId) {
    return jdbc.query(RUN_COLUMNS + " WHERE r.id = ?", ROW, runId).stream()
        .findFirst()
        .orElseThrow(() -> new RunNotFoundException(runId));
  }

  /** A page of a model's runs, newest first. */
  public record RunPage(List<RunRow> items, int page, int size, long totalItems) {}

  /**
   * A model's runs, newest first (ties broken by the time-ordered id, so pages are stable).
   *
   * @throws io.driftwatch.backend.model.ModelNotFoundException if the model does not exist
   */
  @Transactional(readOnly = true)
  public RunPage history(UUID modelId, int page, int size) {
    models.get(modelId);
    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM monitoring_run WHERE model_id = ?", Long.class, modelId);
    List<RunRow> items =
        jdbc.query(
            RUN_COLUMNS
                + " WHERE r.model_id = ? ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
            ROW,
            modelId,
            size,
            (long) page * size);
    return new RunPage(items, page, size, total == null ? 0 : total);
  }

  /**
   * The per-feature results of a run ({@code null} unless it SUCCEEDED).
   *
   * @throws RunNotFoundException if there is no such run
   */
  @Transactional(readOnly = true)
  public Features features(UUID runId) {
    Optional<Features> found =
        jdbc
            .query(
                "SELECT status, (result -> 'features')::text AS features FROM monitoring_run"
                    + " WHERE id = ?",
                (rs, i) -> new Features(RunStatus.valueOf(rs.getString(1)), rs.getString(2)),
                runId)
            .stream()
            .findFirst();
    return found.orElseThrow(() -> new RunNotFoundException(runId));
  }

  private static RunRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new RunRow(
        rs.getObject("id", UUID.class),
        rs.getObject("model_id", UUID.class),
        rs.getObject("reference_profile_id", UUID.class),
        rs.getObject("current_dataset_id", UUID.class),
        rs.getString("sha256"),
        rs.getLong("byte_size"),
        rs.getString("original_filename"),
        RunStatus.valueOf(rs.getString("status")),
        rs.getString("requested_config"),
        rs.getString("engine_version"),
        rs.getString("result_summary"),
        rs.getString("error_kind"),
        rs.getString("error_message"),
        rs.getString("error_details"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
