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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

  /** Unknown paths/static resources: 404 envelope, never the container error page. */
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
