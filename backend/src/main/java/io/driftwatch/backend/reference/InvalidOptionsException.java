package io.driftwatch.backend.reference;

/** The {@code options} form field is not a JSON object. */
public class InvalidOptionsException extends RuntimeException {

  public InvalidOptionsException(String message) {
    super(message);
  }
}
