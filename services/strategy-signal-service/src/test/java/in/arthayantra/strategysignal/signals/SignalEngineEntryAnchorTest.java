package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Adversarial-review follow-up to the B1 / FID P0-1 completeness filter: the coarse-primary exit
 * anchor. {@code generated_at} is the 1m TRIGGER bar instant — for a coarse primary that is the
 * boundary that OPENS bucket k+1 while the entry was EVALUATED on the just-completed bucket k.
 * Anchoring at {@code generatedAt} directly resolves to k+1 once that bucket completes and appends
 * (one bucket after the evaluated bar; the replay harness carries index k), and on the intrabar
 * path it flip-flops k→k+1 across the next boundary refresh. {@link SignalEngine#entryAnchorIndex}
 * subtracts one primary duration for coarse primaries — both the bar-close and intrabar exit paths
 * route through it, so these tests cover both reconstructions.
 */
class SignalEngineEntryAnchorTest {

  private static EngineCandle bar(String iso) {
    return new EngineCandle(
        OffsetDateTime.parse(iso), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), 1000L, null);
  }

  private static Instant instant(String iso) {
    return OffsetDateTime.parse(iso).toInstant();
  }

  private static EngineSeries threeMinuteSeries(String... isoBucketStarts) {
    EngineSeries series = new EngineSeries(new SeriesKey("NFO", "NIFTY26JULFUT", "3m"));
    for (String iso : isoBucketStarts) {
      series.append(bar(iso));
    }
    return series;
  }

  @Test
  void coarseAnchorIsTheEvaluatedBucketNotTheOneTheTriggerOpens() {
    // Entry evaluated on completed bucket k = 09:15; trigger/generatedAt = 09:18 (opens k+1).
    // At entry time the series' last bar is k (the B1 filter excluded the forming 09:18).
    EngineSeries primary =
        threeMinuteSeries("2026-07-03T09:12:00+05:30", "2026-07-03T09:15:00+05:30");
    Instant generatedAt = instant("2026-07-03T09:18:00+05:30");

    assertThat(SignalEngine.entryAnchorIndex(primary, "3m", generatedAt)).isEqualTo(1); // k=09:15
  }

  @Test
  void coarseAnchorIsStableAfterTheTriggerBucketCompletesAndAppends() {
    // The regression the review caught: once the COMPLETED 09:18 (k+1) bucket appends at the next
    // boundary, indexAtOrBefore(generatedAt) would resolve to k+1 — one bucket AFTER the bar the
    // entry evaluated (and a flip-flop vs the pre-append reconstruction on the intrabar path).
    // The minus-duration anchor must still resolve to k on the grown series.
    EngineSeries primary =
        threeMinuteSeries(
            "2026-07-03T09:12:00+05:30",
            "2026-07-03T09:15:00+05:30", // k — the evaluated bucket
            "2026-07-03T09:18:00+05:30", // k+1 — completed and appended after entry
            "2026-07-03T09:21:00+05:30");
    Instant generatedAt = instant("2026-07-03T09:18:00+05:30");

    assertThat(SignalEngine.entryAnchorIndex(primary, "3m", generatedAt)).isEqualTo(1); // still k
  }

  @Test
  void oneMinutePrimaryKeepsTheGeneratedAtBarItself() {
    // For a 1m primary the trigger bar IS the evaluated bar — behavior unchanged by the fix.
    EngineSeries primary = new EngineSeries(new SeriesKey("NFO", "NIFTY26JULFUT", "1m"));
    primary.append(bar("2026-07-03T09:17:00+05:30"));
    primary.append(bar("2026-07-03T09:18:00+05:30"));
    primary.append(bar("2026-07-03T09:19:00+05:30"));

    assertThat(
            SignalEngine.entryAnchorIndex(primary, "1m", instant("2026-07-03T09:18:00+05:30")))
        .isEqualTo(1);
  }

  @Test
  void coarseAnchorFloorsAcrossSessionGapsToTheEvaluatedBar() {
    // Session-open boundary: the 09:15 trigger's just-completed bucket is FRIDAY's last 3m bucket
    // (15:27) — not duration-adjacent. indexAtOrBefore is a floor lookup, so generatedAt − 3m
    // (09:12, before any Monday bar) lands on Friday's last bar: exactly the bar evaluated.
    EngineSeries primary =
        threeMinuteSeries("2026-07-03T15:24:00+05:30", "2026-07-03T15:27:00+05:30");
    Instant mondayOpenTrigger = instant("2026-07-06T09:15:00+05:30");

    assertThat(SignalEngine.entryAnchorIndex(primary, "3m", mondayOpenTrigger)).isEqualTo(1);
  }

  @Test
  void anchorOlderThanTheWarmedWindowClampsToZero() {
    // An entry whose generatedAt predates every warmed bar reconstructs to -1 — clamped to 0,
    // matching the previous Math.max behavior at both call sites.
    EngineSeries primary =
        threeMinuteSeries("2026-07-03T09:15:00+05:30", "2026-07-03T09:18:00+05:30");

    assertThat(
            SignalEngine.entryAnchorIndex(primary, "3m", instant("2026-07-01T09:18:00+05:30")))
        .isZero();
  }
}
