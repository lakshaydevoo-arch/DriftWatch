package io.driftwatch.backend.labels;

import java.util.UUID;

/** The run exists but no labels have been uploaded for it. */
public class LabelsNotFoundException extends RuntimeException {

  LabelsNotFoundException(UUID runId) {
    super("Monitoring run " + runId + " has no labels yet");
  }
}
