package io.driftwatch.backend.run;

import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A bounded executor for monitoring runs: at most {@code concurrency} run at once and at most
 * {@code concurrency + queueCapacity} are accepted at once.
 *
 * <p>Capacity is reserved <em>before</em> any work is done ({@link #reserve}), so a request that
 * would be refused is refused before its file is stored or its run is created. A plain bounded
 * thread-pool queue would only refuse at submission time, after the run already exists.
 */
class RunExecutor implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

  private final Semaphore slots;
  private final int capacity;
  private final ThreadPoolTaskExecutor threads;

  RunExecutor(RunProperties properties) {
    this.capacity = properties.concurrency() + properties.queueCapacity();
    this.slots = new Semaphore(capacity);
    this.threads = new ThreadPoolTaskExecutor();
    threads.setCorePoolSize(properties.concurrency());
    threads.setMaxPoolSize(properties.concurrency());
    // Unbounded in the pool itself; the semaphore already bounds what can be queued.
    threads.setQueueCapacity(Integer.MAX_VALUE);
    threads.setThreadNamePrefix("drift-run-");
    threads.initialize();
  }

  /** A reserved place in the executor. Release it if the run is not submitted after all. */
  final class Slot implements AutoCloseable {
    private boolean open = true;

    /** Runs the task on a worker; the slot is released when the task ends. */
    void submit(Runnable task) {
      if (!open) {
        throw new IllegalStateException("slot already used");
      }
      open = false;
      try {
        threads.execute(() -> {
          try {
            task.run();
          } catch (RuntimeException e) {
            log.error("Monitoring run task failed", e);
          } finally {
            slots.release();
          }
        });
      } catch (RuntimeException e) {
        slots.release();
        throw e;
      }
    }

    /** Releases the slot unless it was submitted. */
    @Override
    public void close() {
      if (open) {
        open = false;
        slots.release();
      }
    }
  }

  /**
   * Reserves a place for one run.
   *
   * @throws RunCapacityExceededException if workers and queue are full
   */
  Slot reserve() {
    if (!slots.tryAcquire()) {
      throw new RunCapacityExceededException(capacity);
    }
    return new Slot();
  }

  /**
   * Stops the workers. Runs still queued or in flight are not waited for: they stay PENDING or
   * RUNNING in the database and are marked FAILED by {@link RunRecovery} at the next start.
   */
  @Override
  public void destroy() {
    threads.shutdown();
  }
}
