package io.driftwatch.backend.analytics;

/**
 * The engine answered in a way that violates the API contract (e.g. a 200 with invalid JSON, or a
 * 4xx that is not a DriftWatch problem). Points to a bug or an incompatible engine version;
 * retrying will not help.
 */
public final class AnalyticsProtocolException extends AnalyticsException {

  AnalyticsProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
