package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineSeries;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Primary-bucket anchoring, sim vs live (task_1b85c64f).
 *
 * <p>The rollup floored on the RAW UTC epoch. IST is UTC+05:30, so a UTC-hour boundary lands at
 * <b>:30 IST</b> — sim 1h bars ran 09:30–10:30 IST while the live 1h series runs on IST clock hours.
 * {@code V029__candles_1h_ist_reanchor.sql} moved the live {@code candles_1h} cagg to
 * {@code time_bucket(INTERVAL '1 hour', bucket, 'Asia/Kolkata')} on 2026-07-04, and that migration's
 * own comment records the pre-fix behaviour as splitting "at :30 IST" — the identical defect, fixed
 * on the live side only. The two were 30 minutes out of phase, so no 1h backtest was ever
 * like-for-like with the series that actually trades.
 *
 * <p>{@link #oneHourAnchorsOnIstClockHours()} fails against the raw-epoch floor and passes against
 * the IST-anchored one; the other two are the paired negatives that keep the fix scoped.
 */
class PrimaryBucketAnchorTest {

  /** 09:47:00 IST — mid-session, deliberately not already on any bucket boundary. */
  private static final OffsetDateTime MID_SESSION =
      OffsetDateTime.parse("2026-06-16T09:47:00+05:30");

  private static LocalTime floorAt(OffsetDateTime ts, Duration interval) {
    return TickwiseGoldenRunner.bucketFloor(ts, interval).atZone(EngineSeries.IST).toLocalTime();
  }

  /** A 1h primary must land on an IST clock hour, matching the live candles_1h cagg. */
  @Test
  void oneHourAnchorsOnIstClockHours() {
    assertThat(floorAt(MID_SESSION, Duration.ofHours(1)))
        .as("09:47 IST floors to 09:00 IST, not the raw-epoch 09:30")
        .isEqualTo(LocalTime.of(9, 0));

    // Walk the whole session: every 1h bucket start is :00, never :30.
    for (int minute = 0; minute < 375; minute++) {
      OffsetDateTime ts = OffsetDateTime.parse("2026-06-16T09:15:00+05:30").plusMinutes(minute);
      assertThat(floorAt(ts, Duration.ofHours(1)).getMinute())
          .as("1h bucket for %s must start on the hour", ts)
          .isZero();
    }
  }

  /**
   * The paired negative that made this fix safe to scope. The IST offset is 19800 s, which divides
   * 3m (÷180 = 110), 5m (÷300 = 66) and 15m (÷900 = 22) EXACTLY — so the shift is a pure no-op for
   * them and not one golden vector needed regenerating. Only 1h leaves a remainder
   * (19800 mod 3600 = 1800), which is precisely the 30-minute phase error. If this ever fails, the
   * anchoring has leaked into intervals it must not touch.
   */
  @Test
  void subHourlyIntervalsAreBitForBitUnchanged() {
    assertThat(floorAt(MID_SESSION, Duration.ofMinutes(3))).isEqualTo(LocalTime.of(9, 45));
    assertThat(floorAt(MID_SESSION, Duration.ofMinutes(5))).isEqualTo(LocalTime.of(9, 45));
    assertThat(floorAt(MID_SESSION, Duration.ofMinutes(15))).isEqualTo(LocalTime.of(9, 45));
  }

  /**
   * 1d is deliberately LEFT on the raw epoch floor. Re-anchoring it would change only the bar's
   * LABEL, not its contents — a 05:30-IST-anchored day and a 00:00-IST-anchored day both wholly
   * contain one 09:15–15:30 session — but goldens compare byte-strings, so it would rewrite the
   * frozen vectors of the eight strategies on a 1d primary for zero behavioural gain. Pinned here so
   * the omission reads as a decision rather than an oversight.
   */
  @Test
  void dailyStaysOnTheRawEpochFloor() {
    assertThat(floorAt(MID_SESSION, Duration.ofDays(1)))
        .as("1d still floors to midnight UTC = 05:30 IST")
        .isEqualTo(LocalTime.of(5, 30));
  }
}
