package in.arthayantra.common.web.servlet;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.ErrorResponse;
import in.arthayantra.common.web.http.ArthaHeaders;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The shared D8 exception-to-envelope mapping for servlet services (A.3/A.4). Every non-2xx body
 * is exactly {@link ErrorResponse}; stack traces never leave the process — the correlation id in
 * {@code details} links the response to the server-side log line.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Domain exceptions carry their own status/code/details. */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
    return ResponseEntity.status(ex.httpStatus()).body(ex.toErrorResponse());
  }

  /** Bean-validation failures: 400 with a field→message map in details (A.4). */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, Object> fields = new LinkedHashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                ErrorCodes.VALIDATION_FAILED, "Request validation failed", Map.of("fields", fields)));
  }

  /** Path/query param type conversion failures (e.g. non-UUID id): 400 VALIDATION_FAILED. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    Map<String, Object> fields = Map.of(ex.getName(), "invalid value: " + ex.getValue());
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                ErrorCodes.VALIDATION_FAILED, "Invalid parameter value", Map.of("fields", fields)));
  }

  /** A required query/form param is absent: 400 VALIDATION_FAILED (was falling through to the 500). */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
    Map<String, Object> fields = Map.of(ex.getParameterName(), "required parameter is missing");
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                ErrorCodes.VALIDATION_FAILED, "Missing required parameter", Map.of("fields", fields)));
  }

  /** Unreadable/malformed request bodies are validation failures, not 500s. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(ErrorResponse.of(ErrorCodes.VALIDATION_FAILED, "Malformed request body"));
  }

  /**
   * A body whose {@code Content-Type} the endpoint does not consume: 415, not the catch-all 500.
   *
   * <p>Spring raises this BEFORE any argument resolution, so the body was never parsed and there is
   * no field-level detail to report — {@code details} carries the offending type and the supported
   * ones instead. {@code contentType} is nullable (a body with no {@code Content-Type} at all also
   * lands here), hence the null-safe rendering.
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("contentType", ex.getContentType() == null ? "(absent)" : ex.getContentType().toString());
    details.put("supported", ex.getSupportedMediaTypes().stream().map(MediaType::toString).toList());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(
            new ErrorResponse(
                ErrorCodes.MEDIA_TYPE_UNSUPPORTED, "Unsupported request Content-Type", details));
  }

  /**
   * A controller that raises {@link ResponseStatusException} has already named its status — honour
   * it instead of dropping to the catch-all 500 (task_e2e01j).
   *
   * <p>Without this mapping every {@code ResponseStatusException(NOT_FOUND, …)} answered <b>500
   * INTERNAL_ERROR</b> with a logged stack trace; the E2E T1b sweep caught it on all five
   * {@code /api/v1/insights/{id}} endpoints, and every future servlet controller reaching for the
   * idiom inherited the same bug. The status→code switch mirrors the reactive gateway's
   * {@code GatewayErrorWebExceptionHandler} exactly so the two D8 handlers cannot disagree; a
   * {@code null} reason falls back to the status' own phrase.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code =
        switch (status) {
          case NOT_FOUND -> ErrorCodes.NOT_FOUND_RESOURCE;
          case SERVICE_UNAVAILABLE -> ErrorCodes.UPSTREAM_UNAVAILABLE;
          case UNAUTHORIZED -> ErrorCodes.AUTH_REQUIRED;
          case FORBIDDEN -> ErrorCodes.AUTH_FORBIDDEN;
          default -> status.is4xxClientError()
              ? ErrorCodes.VALIDATION_FAILED
              : ErrorCodes.INTERNAL_ERROR;
        };
    return ResponseEntity.status(status)
        .body(
            ErrorResponse.of(
                code, ex.getReason() == null ? status.getReasonPhrase() : ex.getReason()));
  }

  /**
   * Unknown paths/static resources: 404 envelope, never the container error page.
   *
   * <p>Kept as its own mapping deliberately: {@code NoResourceFoundException} extends
   * {@code ServletException} (NOT {@link ResponseStatusException}) at Spring 6.2.x, so nothing above
   * can claim it — see {@code GlobalExceptionHandlerTest} for the dispatch pin.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of(ErrorCodes.NOT_FOUND_RESOURCE, "No such resource"));
  }

  /** Last resort: 500 INTERNAL_ERROR with the correlation id, never a stack trace (A.4). */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    String correlationId = MDC.get(ArthaHeaders.MDC_REQUEST_ID);
    log.error("Unhandled exception (correlationId={})", correlationId, ex);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("correlationId", correlationId);
    return ResponseEntity.internalServerError()
        .body(new ErrorResponse(ErrorCodes.INTERNAL_ERROR, "Internal error", details));
  }
}
