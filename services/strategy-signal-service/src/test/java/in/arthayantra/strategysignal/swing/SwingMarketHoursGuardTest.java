package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SwingMarketHoursGuardTest {

  private static final Instant MONDAY_11_00_IST = Instant.parse("2026-07-27T05:30:00Z");
  private static final Instant MONDAY_08_00_IST = Instant.parse("2026-07-27T02:30:00Z");

  @Test
  void refusesDuringIstMarketHours() {
    assertThatThrownBy(() -> guard(Clock.fixed(MONDAY_11_00_IST, ZoneOffset.UTC)).check(false))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.httpStatus()).isEqualTo(422);
              assertThat(exception.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
              assertThat(exception.getMessage()).contains("09:15", "15:30", "force=true");
            });
  }

  @Test
  void forceOverrideAllowsDuringIstMarketHours() {
    assertThatNoException()
        .isThrownBy(() -> guard(Clock.fixed(MONDAY_11_00_IST, ZoneOffset.UTC)).check(true));
  }

  @Test
  void allowsOutsideIstMarketHours() {
    assertThatNoException()
        .isThrownBy(() -> guard(Clock.fixed(MONDAY_08_00_IST, ZoneOffset.UTC)).check(false));
  }

  @Test
  void refusesWhenClockResolutionFailsClosed() {
    Clock brokenClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of("Asia/Kolkata");
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            throw new IllegalStateException("clock unavailable");
          }
        };

    assertThatThrownBy(() -> guard(brokenClock).check(false))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception ->
                assertThat(exception)
                    .satisfies(
                        error -> {
                          assertThat(error.httpStatus()).isEqualTo(422);
                          assertThat(error.code()).isEqualTo(ErrorCodes.VALIDATION_FAILED);
                        }))
        // The MESSAGE is the point, not just the status (cross-vendor review A2): asserting only
        // 422+code let a flatly false "refused during market hours (09:15-15:30)" pass for the
        // uncoverable-date path, which from 2027-01-01 would fire at 22:00 on a Sunday.
        .hasMessageContaining("could not be determined")
        .hasMessageNotContaining("09:15-15:30");
  }

  /** The emergency override must survive a broken clock — that is when it is most needed. */
  @Test
  void forceOverridesEvenWhenTheClockIsBroken() {
    Clock brokenClock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneId.of("Asia/Kolkata");
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            throw new IllegalStateException("clock unavailable");
          }
        };

    assertThatNoException().isThrownBy(() -> guard(brokenClock).check(true));
  }

  private static SwingMarketHoursGuard guard(Clock clock) {
    return new SwingMarketHoursGuard(clock);
  }
}
