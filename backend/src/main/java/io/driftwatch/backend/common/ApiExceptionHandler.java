package io.driftwatch.backend.common;

import io.driftwatch.backend.analytics.AnalyticsProtocolException;
import io.driftwatch.backend.analytics.AnalyticsRejectedException;
import io.driftwatch.backend.analytics.AnalyticsUnavailableException;
import io.driftwatch.backend.dataset.EmptyUploadException;
import io.driftwatch.backend.dataset.InvalidUploadException;
import io.driftwatch.backend.dataset.UploadTooLargeException;
import io.driftwatch.backend.labels.LabelsNotFoundException;
import io.driftwatch.backend.model.ModelNameTakenException;
import io.driftwatch.backend.model.ModelNotFoundException;
import io.driftwatch.backend.reference.InvalidOptionsException;
import io.driftwatch.backend.reference.ReferenceNotFoundException;
import io.driftwatch.backend.run.InvalidConfigException;
import io.driftwatch.backend.run.ReferenceRequiredException;
import io.driftwatch.backend.run.RunCapacityExceededException;
import io.driftwatch.backend.run.RunNotFoundException;
import io.driftwatch.backend.run.RunNotSucceededException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every error into an RFC 9457 problem response ({@code application/problem+json}).
 *
 * <p>Messages describe what is wrong with the request but never include stack traces, SQL or parser
 * internals. Spring's own errors (405, 415, unknown paths) come from the base class and are problem
 * responses too.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** One invalid field or parameter. */
  record FieldProblem(String field, String message) {}

  private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type);
    problem.setTitle(title);
    return problem;
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldProblem> errors =
        ex.getBindingResult().getAllErrors().stream()
            .map(error ->
                new FieldProblem(
                    error instanceof FieldError field ? field.getField() : error.getObjectName(),
                    error.getDefaultMessage()))
            .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::message))
            .toList();
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            ProblemTypes.INVALID_REQUEST,
            "Invalid request",
            "The request body has invalid fields.");
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldProblem> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(result ->
                result.getResolvableErrors().stream()
                    .map(error ->
                        new FieldProblem(
                            result.getMethodParameter().getParameterName(),
                            error.getDefaultMessage())))
            .sorted(Comparator.comparing(FieldProblem::field))
            .toList();
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            ProblemTypes.INVALID_PARAMETER,
            "Invalid parameter",
            "One or more request parameters are invalid.");
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(problem);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.badRequest()
        .body(
            problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST_BODY,
                "Malformed request body",
                describeUnreadable(ex)));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    String expected = describe(ex.getRequiredType());
    return problem(
        HttpStatus.BAD_REQUEST,
        ProblemTypes.INVALID_PARAMETER,
        "Invalid parameter",
        "'" + ex.getName() + "' must be " + expected);
  }

  @ExceptionHandler(ModelNotFoundException.class)
  ProblemDetail handleModelNotFound(ModelNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND, ProblemTypes.MODEL_NOT_FOUND, "Model not found", ex.getMessage());
  }

  @ExceptionHandler(ModelNameTakenException.class)
  ProblemDetail handleNameTaken(ModelNameTakenException ex) {
    return problem(
        HttpStatus.CONFLICT, ProblemTypes.MODEL_NAME_TAKEN, "Model name taken", ex.getMessage());
  }

  @ExceptionHandler(ReferenceNotFoundException.class)
  ProblemDetail handleReferenceNotFound(ReferenceNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND,
        ProblemTypes.REFERENCE_NOT_FOUND,
        "Reference not found",
        ex.getMessage());
  }

  @ExceptionHandler(LabelsNotFoundException.class)
  ProblemDetail handleLabelsNotFound(LabelsNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND, ProblemTypes.LABELS_NOT_FOUND, "Labels not found", ex.getMessage());
  }

  @ExceptionHandler(RunNotFoundException.class)
  ProblemDetail handleRunNotFound(RunNotFoundException ex) {
    return problem(
        HttpStatus.NOT_FOUND, ProblemTypes.RUN_NOT_FOUND, "Run not found", ex.getMessage());
  }

  /** The run exists but has no per-feature results (yet): 409; the run's status is included. */
  @ExceptionHandler(RunNotSucceededException.class)
  ProblemDetail handleRunNotSucceeded(RunNotSucceededException ex) {
    ProblemDetail problem =
        problem(
            HttpStatus.CONFLICT,
            ProblemTypes.RUN_NOT_SUCCEEDED,
            "Run has no results",
            ex.getMessage());
    problem.setProperty("runStatus", ex.getStatus());
    return problem;
  }

  /** The model exists but cannot be monitored yet: a precondition, so 409, not 404. */
  @ExceptionHandler(ReferenceRequiredException.class)
  ProblemDetail handleReferenceRequired(ReferenceRequiredException ex) {
    return problem(
        HttpStatus.CONFLICT,
        ProblemTypes.REFERENCE_REQUIRED,
        "Reference required",
        ex.getMessage());
  }

  /** Refused before anything was stored; the same request can simply be repeated later. */
  @ExceptionHandler(RunCapacityExceededException.class)
  ResponseEntity<ProblemDetail> handleRunCapacity(RunCapacityExceededException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "30")
        .body(
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.RUN_CAPACITY_EXCEEDED,
                "Too many runs",
                ex.getMessage()));
  }

  /**
   * The engine rejected the uploaded data: pass its problem type, detail and errors through, so the
   * client sees exactly why (e.g. {@code invalid-csv: duplicate column names}).
   */
  @ExceptionHandler(AnalyticsRejectedException.class)
  ProblemDetail handleRejected(AnalyticsRejectedException ex) {
    HttpStatus status =
        ex.getStatus() == HttpStatus.CONTENT_TOO_LARGE.value()
            ? HttpStatus.CONTENT_TOO_LARGE
            : HttpStatus.UNPROCESSABLE_CONTENT;
    ProblemDetail problem =
        problem(status, ProblemTypes.of(ex.getKind()), "Rejected by analysis", ex.getDetail());
    if (!ex.getErrors().isEmpty()) {
      problem.setProperty("errors", ex.getErrors());
    }
    return problem;
  }

  @ExceptionHandler(AnalyticsUnavailableException.class)
  ResponseEntity<ProblemDetail> handleUnavailable(AnalyticsUnavailableException ex) {
    // The message names the engine's address; log it, do not return it.
    log.warn("Analytics engine unavailable: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "30")
        .body(
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemTypes.ANALYTICS_UNAVAILABLE,
                "Analysis unavailable",
                "The analytics engine is unavailable; try again later."));
  }

  @ExceptionHandler(AnalyticsProtocolException.class)
  ProblemDetail handleProtocol(AnalyticsProtocolException ex) {
    log.error("Analytics engine broke the API contract", ex);
    return problem(
        HttpStatus.BAD_GATEWAY,
        ProblemTypes.ANALYTICS_PROTOCOL_ERROR,
        "Analysis failed",
        "The analytics engine returned an unexpected response.");
  }

  @ExceptionHandler(UploadTooLargeException.class)
  ProblemDetail handleTooLarge(UploadTooLargeException ex) {
    return problem(
        HttpStatus.CONTENT_TOO_LARGE,
        ProblemTypes.UPLOAD_TOO_LARGE,
        "Upload too large",
        ex.getMessage());
  }

  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .body(
            problem(
                HttpStatus.CONTENT_TOO_LARGE,
                ProblemTypes.UPLOAD_TOO_LARGE,
                "Upload too large",
                "The upload exceeds the size limit."));
  }

  @ExceptionHandler(EmptyUploadException.class)
  ProblemDetail handleEmpty(EmptyUploadException ex) {
    return problem(
        HttpStatus.BAD_REQUEST, ProblemTypes.EMPTY_UPLOAD, "Empty upload", ex.getMessage());
  }

  @ExceptionHandler(InvalidUploadException.class)
  ProblemDetail handleInvalidUpload(InvalidUploadException ex) {
    return problem(
        HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_UPLOAD, "Invalid upload", ex.getMessage());
  }

  @ExceptionHandler({InvalidOptionsException.class, InvalidConfigException.class})
  ProblemDetail handleInvalidJsonField(RuntimeException ex) {
    return problem(
        HttpStatus.BAD_REQUEST,
        ProblemTypes.INVALID_PARAMETER,
        "Invalid parameter",
        ex.getMessage());
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestPart(
      MissingServletRequestPartException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.badRequest()
        .body(
            problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.INVALID_REQUEST,
                "Invalid request",
                "Required part '" + ex.getRequestPartName() + "' is missing."));
  }

  /** Last resort: log the details, return nothing internal to the client. */
  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ProblemTypes.INTERNAL_ERROR,
        "Internal error",
        "An unexpected error occurred.");
  }

  private static String describeUnreadable(HttpMessageNotReadableException ex) {
    Throwable cause = ex.getCause();
    if (cause instanceof UnrecognizedPropertyException unknown) {
      return "Unknown field '" + unknown.getPropertyName() + "'.";
    }
    if (cause instanceof InvalidFormatException invalid && invalid.getTargetType().isEnum()) {
      List<String> allowed =
          Arrays.stream(invalid.getTargetType().getEnumConstants()).map(Object::toString).toList();
      return "Invalid value for '" + path(invalid) + "'; expected one of " + allowed + ".";
    }
    if (cause instanceof DatabindException databind && !databind.getPath().isEmpty()) {
      return "Invalid value for '" + path(databind) + "'.";
    }
    return "The request body is not valid JSON.";
  }

  private static String path(DatabindException ex) {
    return String.join(
        ".",
        ex.getPath().stream()
            .map(ref ->
                ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
            .toList());
  }

  /** Human description of an expected parameter type, e.g. "an integer". */
  private static String describe(Class<?> type) {
    if (type == null) {
      return "a valid value";
    }
    if (type == UUID.class) {
      return "a UUID";
    }
    if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
      return "an integer";
    }
    return "a valid " + type.getSimpleName();
  }
}
