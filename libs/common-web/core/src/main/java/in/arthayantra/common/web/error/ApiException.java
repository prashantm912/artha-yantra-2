package in.arthayantra.common.web.error;

import java.util.Map;

/**
 * Base exception carrying everything the D8 envelope needs. Servlet services map it via the shared
 * {@code GlobalExceptionHandler}; the reactive gateway via its {@code ErrorWebExceptionHandler}
 * equivalent (A.2.4).
 */
public class ApiException extends RuntimeException {

  private final int httpStatus;
  private final String code;
  private final transient Map<String, Object> details;

  /** Envelope exception without details. */
  public ApiException(int httpStatus, String code, String message) {
    this(httpStatus, code, message, Map.of());
  }

  /** Envelope exception with details ({@code null} normalizes to empty). */
  public ApiException(int httpStatus, String code, String message, Map<String, Object> details) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
    this.details = details == null ? Map.of() : details;
  }

  /** The HTTP status this maps to. */
  public int httpStatus() {
    return httpStatus;
  }

  /** The taxonomy code. */
  public String code() {
    return code;
  }

  /** Machine-readable context for the envelope. */
  public Map<String, Object> details() {
    return details;
  }

  /** The envelope this exception renders to. */
  public ErrorResponse toErrorResponse() {
    return new ErrorResponse(code, getMessage(), details);
  }
}
