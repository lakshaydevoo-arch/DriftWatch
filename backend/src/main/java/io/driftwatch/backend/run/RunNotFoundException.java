package io.driftwatch.backend.run;

import java.util.UUID;

/** No monitoring run has this id. */
public class RunNotFoundException extends RuntimeException {

  RunNotFoundException(UUID runId) {
    super("Monitoring run " + runId + " does not exist");
  }
}
