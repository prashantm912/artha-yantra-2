package in.arthayantra.marketdata.options;

import in.arthayantra.black76.Black76;
import in.arthayantra.common.web.time.Ist;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.OptionalDouble;

/**
 * ACT/365 calendar time to 15:30 IST on the expiry date (B-10 day-count pin). After the 15:30
 * cutoff IV/Greeks are null BY DEFINITION (empty here); inside the final five minutes the solver
 * clamps via {@link Black76#T_MIN}.
 */
public final class ExpiryClock {

  private static final LocalTime EXPIRY_CUTOFF = LocalTime.of(15, 30);
  private static final double SECONDS_PER_YEAR = 365.0 * 24 * 60 * 60;

  private ExpiryClock() {}

  /** Years to expiry, or empty when the option is past its 15:30 IST cutoff. */
  public static OptionalDouble yearsToExpiry(Instant now, LocalDate expiry) {
    OffsetDateTime cutoff = expiry.atTime(EXPIRY_CUTOFF).atOffset(Ist.OFFSET);
    long secondsLeft = java.time.Duration.between(now, cutoff.toInstant()).toSeconds();
    if (secondsLeft <= 0) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(secondsLeft / SECONDS_PER_YEAR);
  }
}
