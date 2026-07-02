package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Universal control-bar contract: Mode·Name·Date·Expiry·Interval. Lives in the {@code options}
 * module API (not {@code analytics}) so the {@code futures} analytics endpoints can reuse it
 * without reaching into another module's internals (Spring Modulith).
 */
public record OiQuery(
    boolean live, String name, LocalDate date, OiInterval interval, LocalDate expiry) {

  public static OiQuery of(String mode, String name, String date, String interval, String expiry) {
    if (name == null || name.isBlank()) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "name is required");
    }
    boolean live = mode == null || mode.isBlank() || "live".equalsIgnoreCase(mode);
    OiInterval iv =
        interval == null || interval.isBlank() ? OiInterval.M3 : OiInterval.parse(interval);
    LocalDate d = parseDate(date, "date");
    LocalDate e = parseDate(expiry, "expiry");
    if (!live && d == null) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "history mode requires date");
    }
    if (live) {
      // T4 (audit 2026-07-02 §9.3): live never serves a historical day — a stale date param left
      // over from a History session must not time-travel a "Live"-labelled response.
      d = null;
    }
    return new OiQuery(live, name.trim(), d, iv, e);
  }

  private static LocalDate parseDate(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException ex) {
      throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, field + " must be ISO yyyy-MM-dd");
    }
  }
}
