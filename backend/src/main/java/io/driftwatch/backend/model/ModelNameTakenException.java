package io.driftwatch.backend.model;

/** A model with the requested name already exists. */
public class ModelNameTakenException extends RuntimeException {

  private final String name;

  public ModelNameTakenException(String name, Throwable cause) {
    super("A model named '" + name + "' already exists", cause);
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
