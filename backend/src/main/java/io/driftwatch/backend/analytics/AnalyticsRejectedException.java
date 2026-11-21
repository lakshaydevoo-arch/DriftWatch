package io.driftwatch.backend.analytics;

import java.util.List;
import java.util.Map;

/**
 * The engine understood the request and rejected its <em>content</em> (an RFC 9457 4xx problem),
 * e.g. an invalid CSV or a batch that does not match the reference schema. Retrying the same input
 * will fail again; the problem should be shown to the user.
 */
public final class AnalyticsRejectedException extends AnalyticsException {

  private final int status;
  private final String kind;
  private final String detail;
  private final List<Map<String, Object>> errors;

  public AnalyticsRejectedException(
      int status, String kind, String detail, List<Map<String, Object>> errors) {
    super("Analytics engine rejected the request (" + status + " " + kind + "): " + detail, null);
    this.status = status;
    this.kind = kind;
    this.detail = detail;
    this.errors = List.copyOf(errors);
  }

  /** HTTP status returned by the engine (4xx). */
  public int getStatus() {
    return status;
  }

  /** Problem kind, the part after {@code urn:driftwatch:problem:}, e.g. {@code invalid-csv}. */
  public String getKind() {
    return kind;
  }

  public String getDetail() {
    return detail;
  }

  /** The problem's {@code errors} list, if any (empty otherwise). */
  public List<Map<String, Object>> getErrors() {
    return errors;
  }
}
