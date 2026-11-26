package io.driftwatch.backend.run;

import java.util.UUID;

/** Per-feature results were requested for a run that has none (not SUCCEEDED, at least yet). */
public class RunNotSucceededException extends RuntimeException {

  private final RunStatus status;

  RunNotSucceededException(UUID runId, RunStatus status) {
    super(
        "Monitoring run "
            + runId
            + " is "
            + status
            + "; per-feature results exist only for SUCCEEDED runs");
    this.status = status;
  }

  public RunStatus getStatus() {
    return status;
  }
}
