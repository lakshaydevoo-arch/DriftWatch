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

  private ProblemTypes() {}

  private static URI of(String kind) {
    return URI.create(PREFIX + kind);
  }
}
