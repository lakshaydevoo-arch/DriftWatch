package io.driftwatch.backend.run;

import java.util.UUID;

/** A run was requested for a model that has no reference dataset to compare against. */
public class ReferenceRequiredException extends RuntimeException {

  ReferenceRequiredException(UUID modelId) {
    super("Model " + modelId + " has no reference dataset; register one before starting runs");
  }
}
