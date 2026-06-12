package in.arthayantra.common.web.error;

import java.util.Map;

/**
 * The D8 error envelope (A.4 / COMMON §8.3) — every non-2xx response from every service carries
 * exactly this shape. Never a stack trace, never a {@code {"success": false}} body with HTTP 200.
 *
 * <p>{@code details} values may be {@code null} (e.g. {@code "jobId": null}), so the map is kept
 * as supplied; a {@code null} map normalizes to an empty one.
 *
 * @param code SCREAMING_SNAKE error code from the {@link ErrorCodes} taxonomy
 * @param message human-readable, log-safe summary (no secrets, no stack traces)
 * @param details machine-readable context (field maps, retry hints, correlation ids)
 */
public record ErrorResponse(String code, String message, Map<String, Object> details) {

  /** Normalizes a {@code null} details map to an empty one. */
  public ErrorResponse {
    if (details == null) {
      details = Map.of();
    }
  }

  /** Envelope with no details. */
  public static ErrorResponse of(String code, String message) {
    return new ErrorResponse(code, message, Map.of());
  }
}
