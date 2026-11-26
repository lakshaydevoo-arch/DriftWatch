package io.driftwatch.backend;

import org.springframework.jdbc.core.JdbcTemplate;

/** Test helper for the shared test database (one per cached Spring context). */
public final class TestDatabase {

  /** Children before parents, so foreign keys (ON DELETE RESTRICT) never block a delete. */
  private static final String[] TABLES_IN_DELETE_ORDER = {
    "monitoring_run", "reference_profile", "dataset", "monitored_model"
  };

  private TestDatabase() {}

  /** Deletes all application rows. Call before and after tests that commit. */
  public static void clear(JdbcTemplate jdbc) {
    for (String table : TABLES_IN_DELETE_ORDER) {
      jdbc.update("DELETE FROM " + table);
    }
  }
}
