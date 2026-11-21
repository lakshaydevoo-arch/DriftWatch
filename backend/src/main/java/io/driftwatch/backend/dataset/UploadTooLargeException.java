package io.driftwatch.backend.dataset;

/** An upload exceeded the configured size limit. Nothing was stored. */
public class UploadTooLargeException extends RuntimeException {

  private final long maxBytes;

  public UploadTooLargeException(long maxBytes) {
    super("Upload exceeds the limit of " + maxBytes + " bytes");
    this.maxBytes = maxBytes;
  }

  public long getMaxBytes() {
    return maxBytes;
  }
}
