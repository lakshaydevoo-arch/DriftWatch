package io.driftwatch.backend.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * At startup, marks runs left unfinished by the previous process as FAILED ({@code interrupted}).
 *
 * <p>The run queue lives in memory, so such runs would otherwise stay PENDING or RUNNING forever.
 * They are failed rather than re-queued: the client learns what happened and can resubmit, and a
 * batch that crashed the engine is not retried in a loop.
 *
 * <p>Runs after all beans are created (Flyway has migrated) but <em>before</em> the web server
 * accepts requests, so it can never fail a run created by this process.
 *
 * <p>Assumes a single backend instance. With several instances sharing one database, a starting
 * instance would fail the runs another one is executing; that would need a lease or owner column.
 */
@Component
class RunRecovery implements SmartInitializingSingleton {

  static final String MESSAGE =
      "The backend stopped before this run finished; submit the batch again.";

  private static final Logger log = LoggerFactory.getLogger(RunRecovery.class);

  private final MonitoringRunRepository runs;
  private final TransactionTemplate transactions;

  RunRecovery(MonitoringRunRepository runs, TransactionTemplate transactions) {
    this.runs = runs;
    this.transactions = transactions;
  }

  @Override
  public void afterSingletonsInstantiated() {
    recover();
  }

  /**
   * Marks all PENDING and RUNNING runs FAILED.
   *
   * @return how many runs were marked
   */
  int recover() {
    Integer failed =
        transactions.execute(
            status -> runs.failUnfinished(RunWorker.INTERRUPTED, MESSAGE, MonitoringRun.now()));
    int count = failed == null ? 0 : failed;
    if (count > 0) {
      log.warn("Marked {} unfinished monitoring run(s) as FAILED after a restart", count);
    }
    return count;
  }
}
