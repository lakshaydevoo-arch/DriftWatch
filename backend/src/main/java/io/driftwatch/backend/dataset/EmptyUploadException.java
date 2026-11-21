package io.driftwatch.backend.dataset;

/** An upload contained no bytes. Nothing was stored. */
public class EmptyUploadException extends RuntimeException {

  public EmptyUploadException() {
    super("Upload is empty");
  }
}
