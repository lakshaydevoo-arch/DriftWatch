package io.driftwatch.backend.run;

/** The {@code config} form field is not a JSON object. Its content is checked by the engine. */
public class InvalidConfigException extends RuntimeException {

  InvalidConfigException(String message) {
    super(message);
  }
}
