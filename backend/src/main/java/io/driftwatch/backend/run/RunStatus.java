package io.driftwatch.backend.run;

/**
 * Where a monitoring run is in its lifecycle: {@code PENDING → RUNNING → SUCCEEDED | FAILED}. A
 * {@code PENDING} run can also go straight to {@code FAILED} when it is interrupted.
 */
public enum RunStatus {
  /** Created and queued; the engine has not been called yet. */
  PENDING,
  /** A worker is waiting for the engine. */
  RUNNING,
  /** The engine's complete result is stored. */
  SUCCEEDED,
  /** No result; the reason is stored. */
  FAILED;

  /** Whether the run has finished (successfully or not). */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED;
  }
}
