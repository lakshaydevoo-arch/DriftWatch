package io.driftwatch.backend.analytics;

/** Base class of all analytics engine failures. */
public abstract sealed class AnalyticsException extends RuntimeException
    permits AnalyticsRejectedException, AnalyticsUnavailableException, AnalyticsProtocolException {

  AnalyticsException(String message, Throwable cause) {
    super(message, cause);
  }
}
