package io.driftwatch.backend.run;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MonitoringRunRepository extends JpaRepository<MonitoringRun, UUID> {

  /**
   * PENDING → RUNNING, only if the run is still PENDING. A single conditional UPDATE, so two
   * workers (or a worker and startup recovery) can never both start the same run.
   *
   * @return 1 if this caller started the run, 0 otherwise
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE monitoring_run
             SET status = 'RUNNING', started_at = GREATEST(:now, created_at)
           WHERE id = :id AND status = 'PENDING'
          """,
      nativeQuery = true)
  int claim(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Marks every unfinished run FAILED. Used at startup: the queue lives in memory, so a run that
   * was PENDING or RUNNING when the previous process stopped will never finish on its own.
   *
   * @return the number of runs marked FAILED
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE monitoring_run
             SET status = 'FAILED',
                 error_kind = :kind,
                 error_message = :message,
                 finished_at = GREATEST(:now, COALESCE(started_at, created_at))
           WHERE status IN ('PENDING', 'RUNNING')
          """,
      nativeQuery = true)
  int failUnfinished(
      @Param("kind") String kind, @Param("message") String message, @Param("now") Instant now);
}
