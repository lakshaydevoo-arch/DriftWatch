package io.driftwatch.backend.reference;

import java.util.UUID;

/** The model exists but has no reference dataset yet. */
public class ReferenceNotFoundException extends RuntimeException {

  public ReferenceNotFoundException(UUID modelId) {
    super("Model " + modelId + " has no reference dataset yet");
  }
}
