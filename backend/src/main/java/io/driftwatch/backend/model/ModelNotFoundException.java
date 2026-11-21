package io.driftwatch.backend.model;

import java.util.UUID;

/** No model with the requested id exists. */
public class ModelNotFoundException extends RuntimeException {

  private final UUID id;

  public ModelNotFoundException(UUID id) {
    super("No model with id " + id);
    this.id = id;
  }

  public UUID getId() {
    return id;
  }
}
