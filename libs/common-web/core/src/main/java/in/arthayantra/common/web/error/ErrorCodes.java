package in.arthayantra.common.web.error;

/**
 * SCREAMING_SNAKE error-code constants (A.4 / COMMON §8.3 taxonomy). Codes are grouped by family
 * prefix; the HTTP status family each maps to is fixed by the taxonomy table.
 */
public final class ErrorCodes {

  // ---- VALIDATION_* (400) ----
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  // ---- NOT_FOUND_* (404) ----
  public static final String NOT_FOUND_RESOURCE = "NOT_FOUND_RESOURCE";

  // ---- INTERNAL_* (500) ----
  public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  private ErrorCodes() {}
}
