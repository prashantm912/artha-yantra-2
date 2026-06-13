package in.arthayantra.backtest.regime;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the computed (never declared) regime labeler (guard 6, §D.4): the trend × vol label
 * math, the four labels, the <b>T−1 boundary</b> (entry-day close is never consulted), and
 * determinism. Series are synthetic, deterministic, and carry ≥ 260 trading days of warm-up so the
 * 200-day SMA and 1-year vol median are fully defined.
 */
class RegimeLabelerTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private final RegimeLabeler labeler = new RegimeLabeler();

  /** Calendar-day-indexed synthetic daily series (no weekend logic needed — the labeler is index-based). */
  private static List<EngineCandle> series(int n, IntToDoubleFunction closeFn) {
    List<EngineCandle> out = new ArrayList<>(n);
    LocalDate day = LocalDate.parse("2024-01-01");
    for (int i = 0; i < n; i++) {
      // exact BigDecimal closes (no quantization) — these unit tests probe the labeler's vol math,
      // not the seeder's paisa rounding, so any rounding asymmetry must not perturb the assertions.
      BigDecimal close = new BigDecimal(Double.toString(closeFn.applyAsDouble(i)));
      OffsetDateTime ts = day.plusDays(i).atStartOfDay().atOffset(IST);
      out.add(new EngineCandle(ts, close, close, close, close, 0L, null));
    }
    return out;
  }

  @Test
  void emitsLabelsOnlyForDaysWithFullWarmup() {
    int n = RegimeLabeler.WARMUP_SESSIONS + 5;
    Map<LocalDate, RegimeLabel> labels = labeler.label(series(n, i -> 20000.0 + i));
    // The first labelable entry day sits at index WARMUP_SESSIONS; everything earlier lacks depth.
    assertThat(labels).hasSize(n - RegimeLabeler.WARMUP_SESSIONS);
    LocalDate firstLabelled = LocalDate.parse("2024-01-01").plusDays(RegimeLabeler.WARMUP_SESSIONS);
    assertThat(labels).containsKey(firstLabelled);
    assertThat(labels).doesNotContainKey(firstLabelled.minusDays(1));
  }

  @Test
  void steadyUptrendLowVolIsUpQuiet() {
    // Rising price (close above the trailing 200-SMA -> UP) with a NOISY early history that lifts the
    // 1-year vol median, then a CALM recent stretch whose tiny vol sits well below that median ->
    // unambiguously QUIET (no reliance on floating-point ties).
    int warm = RegimeLabeler.WARMUP_SESSIONS;
    int n = warm + 30;
    Map<LocalDate, RegimeLabel> labels =
        labeler.label(series(n, i -> risingWithCalmTail(i, warm, +1.0)));
    assertThat(labels.values()).containsOnly(RegimeLabel.UP_QUIET);
  }

  @Test
  void steadyDowntrendLowVolIsDownQuiet() {
    // Falling price (close below the trailing SMA -> DOWN) with the same noisy-history / calm-tail
    // construction so the recent vol is clearly below its 1-year median -> QUIET.
    int warm = RegimeLabeler.WARMUP_SESSIONS;
    int n = warm + 30;
    Map<LocalDate, RegimeLabel> labels =
        labeler.label(series(n, i -> risingWithCalmTail(i, warm, -1.0)));
    assertThat(labels.values()).containsOnly(RegimeLabel.DOWN_QUIET);
  }

  /**
   * A monotone trend ({@code dir} = +1 rising, −1 falling) whose EARLY history carries a deterministic
   * alternating shock (a high vol baseline lifting the 1-year median) and whose RECENT ~50 sessions
   * are calm — so every labeled tail day reads QUIET unambiguously, the trend sign chosen by {@code
   * dir}. Returns stay small enough that price never crosses back over its own 200-SMA.
   */
  private static double risingWithCalmTail(int i, int warm, double dir) {
    double base = 30000.0 + dir * i * 8.0; // gentle linear trend in the chosen direction
    int calmStart = warm - 40; // the last ~40 warm-up days + all tail days are calm
    if (i < calmStart) {
      return base * (1.0 + ((i % 2 == 0) ? 0.012 : -0.012)); // noisy history -> high vol median
    }
    return base; // calm recent window -> vol well below the median
  }

  @Test
  void allFourLabelsAreReachable() {
    // Engineer the tail through all four regimes. The vol dimension is driven by a multiplicative
    // shock factor: a flat (zero-vol) geometric base reads QUIET; injecting a large alternating
    // ±jump for a stretch spikes the 20-day vol above its near-zero trailing median -> TURBULENT.
    // The trend dimension is driven by the geometric drift sign (above/below the 200-SMA).
    int warm = RegimeLabeler.WARMUP_SESSIONS;
    int n = warm + 500;
    double[] close = new double[n];
    close[0] = 20000.0;
    for (int i = 1; i < n; i++) {
      int k = i - warm;
      double drift;
      double shock;
      if (i < warm || k < 60) {
        drift = 1.0008; // quiet uptrend (rising, flat vol)
        shock = 1.0;
      } else if (k < 140) {
        drift = 1.0008; // turbulent uptrend (still rising, large alternating shocks)
        shock = (k % 2 == 0) ? 1.03 : (1.0 / 1.03);
      } else if (k < 320) {
        drift = 0.9990; // turbulent downtrend (falling under the SMA, still shocked)
        shock = (k % 2 == 0) ? 1.03 : (1.0 / 1.03);
      } else {
        drift = 0.9992; // quiet downtrend (still falling, vol decays back under the median)
        shock = 1.0;
      }
      close[i] = close[i - 1] * drift * shock;
    }
    List<EngineCandle> s = series(n, i -> close[i]);

    Map<LocalDate, RegimeLabel> labels = labeler.label(s);
    assertThat(labels.values()).contains(RegimeLabel.UP_QUIET);
    assertThat(labels.values()).contains(RegimeLabel.UP_TURBULENT);
    assertThat(labels.values()).contains(RegimeLabel.DOWN_TURBULENT);
    assertThat(labels.values()).contains(RegimeLabel.DOWN_QUIET);
  }

  @Test
  void labelForDayTUsesOnlyDataThroughTMinus1Close() {
    // THE FAIL-CONDITION GUARD: the entry day's own close must never move its label. Build a steady
    // uptrend (every labeled day is UP_QUIET), then replace the LAST day's close with an absurd
    // crash. If the label consulted day T's own close, the last day would flip to DOWN; it must NOT.
    int n = RegimeLabeler.WARMUP_SESSIONS + 10;
    List<EngineCandle> rising = new ArrayList<>(series(n, i -> 20000.0 + i * 5.0));
    Map<LocalDate, RegimeLabel> baseline = labeler.label(rising);

    // Corrupt ONLY the final day's close to a crash value.
    EngineCandle last = rising.get(n - 1);
    EngineCandle crashed =
        new EngineCandle(last.bucketStart(), last.open(), last.high(), last.low(),
            BigDecimal.valueOf(1.0), 0L, null);
    List<EngineCandle> withCrash = new ArrayList<>(rising);
    withCrash.set(n - 1, crashed);
    Map<LocalDate, RegimeLabel> withLookahead = labeler.label(withCrash);

    LocalDate lastDay = last.bucketStart().toLocalDate();
    // The last day's label is unchanged: it is computed off T−1's close, not its own crash close.
    assertThat(withLookahead.get(lastDay)).isEqualTo(baseline.get(lastDay));
    assertThat(withLookahead.get(lastDay)).isEqualTo(RegimeLabel.UP_QUIET);
    // (Sanity: the corrupted day exists and is the only difference vs the baseline series.)
    assertThat(withLookahead).isEqualTo(baseline);
  }

  @Test
  void isDeterministicAcrossRuns() {
    List<EngineCandle> s =
        series(
            RegimeLabeler.WARMUP_SESSIONS + 120,
            i -> 20000.0 + i * 3.0 + 150.0 * Math.sin(i / 9.0));
    Map<LocalDate, RegimeLabel> first = labeler.label(s);
    Map<LocalDate, RegimeLabel> second = labeler.label(s);
    assertThat(second).isEqualTo(first);
  }

  @Test
  void tooShortSeriesYieldsNoLabels() {
    assertThat(labeler.label(series(50, i -> 20000.0 + i))).isEmpty();
  }

  @Test
  void mixCountsLabelsOverEntryDaysOrderedByLabel() {
    Map<LocalDate, RegimeLabel> labels =
        labeler.label(series(RegimeLabeler.WARMUP_SESSIONS + 6, i -> 20000.0 + i * 5.0));
    List<LocalDate> days = new ArrayList<>(labels.keySet());
    Map<RegimeLabel, Integer> mix = RegimeLabeler.mix(days, labels);
    // all entries are UP_QUIET here; the mix has all four keys with the others at 0 (never a missing
    // key — the leaderboard renders a stable four-column shape).
    assertThat(mix).containsKeys(
        RegimeLabel.UP_QUIET, RegimeLabel.UP_TURBULENT, RegimeLabel.DOWN_QUIET,
        RegimeLabel.DOWN_TURBULENT);
    assertThat(mix.get(RegimeLabel.UP_QUIET)).isEqualTo(days.size());
    assertThat(mix.get(RegimeLabel.DOWN_QUIET)).isZero();
  }
}
