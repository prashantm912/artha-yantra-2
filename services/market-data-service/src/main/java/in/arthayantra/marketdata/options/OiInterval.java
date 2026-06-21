package in.arthayantra.marketdata.options;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.Duration;
import java.util.Arrays;

/** oipulse OI interval set (D3). Downsample bucket for time_bucket(). */
public enum OiInterval {
  M1(1),
  M3(3),
  M5(5),
  M10(10),
  M15(15),
  M30(30),
  M60(60);

  private final int minutes;

  OiInterval(int minutes) {
    this.minutes = minutes;
  }

  public String token() {
    return minutes + "m";
  }

  public Duration bucket() {
    return Duration.ofMinutes(minutes);
  }

  /**
   * Literal for SQL {@code public.time_bucket(INTERVAL '<x> minutes', ts)}. Derived from a fixed int
   * — no injection surface.
   */
  public String pgInterval() {
    return minutes + " minutes";
  }

  public static OiInterval parse(String token) {
    return Arrays.stream(values())
        .filter(i -> i.token().equals(token))
        .findFirst()
        .orElseThrow(
            () ->
                new ApiException(
                    400,
                    ErrorCodes.VALIDATION_INTERVAL_UNSUPPORTED,
                    "interval must be one of 1m,3m,5m,10m,15m,30m,60m"));
  }
}
