package io.driftwatch.backend.common;

import java.net.URI;

/**
 * Stable RFC 9457 problem {@code type} identifiers. Clients branch on these, never on messages. The
 * same {@code urn:driftwatch:problem:} scheme is used by the analytics engine.
 */
public final class ProblemTypes {

  private static final String PREFIX = "urn:driftwatch:problem:";

  public static final URI INVALID_REQUEST = of("invalid-request");
  public static final URI MALFORMED_REQUEST_BODY = of("malformed-request-body");
  public static final URI INVALID_PARAMETER = of("invalid-parameter");
  public static final URI MODEL_NOT_FOUND = of("model-not-found");
  public static final URI MODEL_NAME_TAKEN = of("model-name-taken");
  public static final URI INTERNAL_ERROR = of("internal-error");
  public static final URI REFERENCE_NOT_FOUND = of("reference-not-found");
  public static final URI UPLOAD_TOO_LARGE = of("upload-too-large");
  public static final URI EMPTY_UPLOAD = of("empty-upload");
  public static final URI INVALID_UPLOAD = of("invalid-upload");
  public static final URI ANALYTICS_UNAVAILABLE = of("analytics-unavailable");
  public static final URI ANALYTICS_PROTOCOL_ERROR = of("analytics-protocol-error");
  public static final URI RUN_NOT_FOUND = of("run-not-found");
  public static final URI REFERENCE_REQUIRED = of("reference-required");
  public static final URI RUN_CAPACITY_EXCEEDED = of("run-capacity-exceeded");

  private ProblemTypes() {}

  /**
   * A problem type by kind. Used to pass the analytics engine's problem types (e.g. {@code
   * invalid-csv}) through unchanged, since both services share the scheme.
   */
  public static URI of(String kind) {
    return URI.create(PREFIX + kind);
  }
}
