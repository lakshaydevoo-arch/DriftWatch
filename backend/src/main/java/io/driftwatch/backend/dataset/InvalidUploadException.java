package io.driftwatch.backend.dataset;

/** Upload metadata (such as the file name) is invalid. Nothing was stored. */
public class InvalidUploadException extends RuntimeException {

  public InvalidUploadException(String message) {
    super(message);
  }
}
