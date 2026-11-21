package io.driftwatch.backend.analytics;

/**
 * The engine could not be reached or failed (connection refused, timeout, 5xx). The same request
 * may succeed later.
 */
public final class AnalyticsUnavailableException extends AnalyticsException {

  AnalyticsUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
