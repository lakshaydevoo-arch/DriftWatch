package io.driftwatch.backend.common;

import io.driftwatch.backend.model.ModelNameTakenException;
import io.driftwatch.backend.model.ModelNotFoundException;
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
