package io.driftwatch.backend.run;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code driftwatch.runs.*}: how many runs execute at once and how many may wait.
 *
 * @param concurrency runs sent to the engine at the same time. Each one holds a whole batch in the
 *     engine's memory, so this stays small.
 * @param queueCapacity accepted runs that may wait for a free worker. When workers and queue are
 *     full, new runs are refused (HTTP 503) instead of piling up in memory.
 */
@ConfigurationProperties("driftwatch.runs")
public record RunProperties(int concurrency, int queueCapacity) {

  public RunProperties {
    if (concurrency < 1) {
      throw new IllegalArgumentException("driftwatch.runs.concurrency must be at least 1");
    }
    if (queueCapacity < 0) {
      throw new IllegalArgumentException("driftwatch.runs.queue-capacity must not be negative");
    }
  }
}
