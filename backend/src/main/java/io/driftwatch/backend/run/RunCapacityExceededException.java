package io.driftwatch.backend.run;

/** All workers are busy and the queue is full; nothing was stored. Retry later. */
public class RunCapacityExceededException extends RuntimeException {

  RunCapacityExceededException(int capacity) {
    super("Too many monitoring runs in progress (limit " + capacity + "); try again later");
  }
}
