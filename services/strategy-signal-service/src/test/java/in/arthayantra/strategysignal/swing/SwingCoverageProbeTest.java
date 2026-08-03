package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the 2026-08-03 investigation's central finding: a missing daily bar does not shorten a
 * row-based window, it STRETCHES it, and whether that matters depends on window LENGTH versus gap
 * DISTANCE — not on bar count. {@link #gapOutsideTheWindowIsNotReported} is the discriminating case;
 * without it the probe could be a bar-count floor in disguise and every other test here would still
 * pass.
 */
class SwingCoverageProbeTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final MarketCalendar NSE = MarketCalendar.nse();

  /** Daily bars for every NSE trading day in [from, to], minus {@code skip}. */
  private static List<EngineCandle> series(LocalDate from, LocalDate to, LocalDate... skip) {
    List<LocalDate> skipped = List.of(skip);
    List<EngineCandle> out = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (!NSE.isTradingDay(d) || skipped.contains(d)) {
        continue;
      }
      out.add(
          new EngineCandle(
              d.atStartOfDay().atOffset(IST), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
              BigDecimal.ONE, 1L));
    }
    return out;
  }

  private static StrategyDefinition definition(
      List<StrategyDefinition.IndicatorSpec> indicators,
      List<StrategyDefinition.ExitRuleSpec> exits) {
    return new StrategyDefinition(
        "t", "1", "1d", List.of(), indicators, StrategyDefinition.Direction.LONG, null, null, exits,
        null, null);
  }

  private static StrategyDefinition.IndicatorSpec indicator(String alias, Map<String, Object> p) {
    return new StrategyDefinition.IndicatorSpec("SMA", alias, "1d", p, null, false, null, null);
  }

  @Test
  @DisplayName("a session missing inside the probed window is reported")
  void missingSessionInsideWindowIsReported() {
    // 2026-06-18 and 06-19 are the real sessions that were absent from marketdata.candles.
    List<EngineCandle> bars =
        series(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19));

    SwingCoverageProbe.Coverage c = SwingCoverageProbe.probe(bars, 50, NSE);

    assertThat(c.determinable()).isTrue();
    assertThat(c.incomplete()).isTrue();
    assertThat(c.missing())
        .containsExactly(LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19));
  }

  @Test
  @DisplayName("THE DISCRIMINATOR: a gap OUTSIDE the probed window is not reported")
  void gapOutsideTheWindowIsNotReported() {
    // Same two missing sessions, but probed with a 14-bar window (Manas's ATR-20 class of rule
    // reaches nowhere near June from an August bar). On 2026-08-03 the gap sat 30-35 rows back, so a
    // short window genuinely was unaffected — reporting it here would be a false positive and would
    // have wrongly implicated Manas, whose exit rules were verified clean.
    List<EngineCandle> bars =
        series(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19));

    SwingCoverageProbe.Coverage shallow = SwingCoverageProbe.probe(bars, 14, NSE);

    assertThat(shallow.determinable()).isTrue();
    assertThat(shallow.incomplete()).as("a 14-bar window must not see a gap 30 rows back").isFalse();
    assertThat(shallow.missing()).isEmpty();
    // and the deep window over the SAME bars still sees it — proving the difference is the window,
    // not the data
    assertThat(SwingCoverageProbe.probe(bars, 50, NSE).incomplete()).isTrue();
  }

  @Test
  @DisplayName("a complete window reports complete")
  void completeWindowIsComplete() {
    List<EngineCandle> bars = series(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 3));

    SwingCoverageProbe.Coverage c = SwingCoverageProbe.probe(bars, 50, NSE);

    assertThat(c.determinable()).isTrue();
    assertThat(c.incomplete()).isFalse();
    assertThat(c.missing()).isEmpty();
  }

  @Test
  @DisplayName("row count is preserved by a gap — a minBars floor cannot detect this")
  void barCountFloorCannotDetectAStretchedWindow() {
    // The naive fix, falsified as a test: the gapped series still clears any plausible minBars floor,
    // because a gap removes bars from the RANGE, not from the WINDOW.
    List<EngineCandle> gapped =
        series(LocalDate.of(2025, 3, 1), LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19));

    assertThat(gapped).hasSizeGreaterThan(300);
    assertThat(gapped.size()).as("clears a 60-bar floor comfortably").isGreaterThan(60);
    assertThat(SwingCoverageProbe.probe(gapped, 50, NSE).incomplete())
        .as("yet the 50-bar window is stretched")
        .isTrue();
  }

  @Test
  @DisplayName("never throws, and makes no claim, for a year outside the bundled calendar")
  void uncoveredYearDegradesInsteadOfThrowing() {
    // MarketCalendar.isTradingDay raises IllegalArgumentException outside the bundled years (CD-2).
    // The 520-day warmup window crosses that boundary every January, and this sits on the money path.
    List<EngineCandle> bars = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      bars.add(
          new EngineCandle(
              LocalDate.of(2019, 3, 1).plusDays(i).atStartOfDay().atOffset(IST),
              BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1L));
    }

    assertThatCode(() -> SwingCoverageProbe.probe(bars, 50, NSE)).doesNotThrowAnyException();
    SwingCoverageProbe.Coverage c = SwingCoverageProbe.probe(bars, 50, NSE);
    assertThat(c.determinable()).isFalse();
    assertThat(c.incomplete()).as("undeterminable must never read as incomplete").isFalse();
  }

  @Test
  @DisplayName("degrades rather than throwing on empty/absent inputs")
  void emptyInputsAreUndeterminable() {
    assertThat(SwingCoverageProbe.probe(List.of(), 50, NSE).determinable()).isFalse();
    assertThat(SwingCoverageProbe.probe(null, 50, NSE).determinable()).isFalse();
    assertThat(
            SwingCoverageProbe.probe(series(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 3)), 0, NSE)
                .determinable())
        .isFalse();
    assertThat(
            SwingCoverageProbe.probe(
                    series(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 3)), 50, null)
                .determinable())
        .isFalse();
  }

  @Test
  @DisplayName("lookback takes the max across indicators AND exit rules")
  void lookbackSpansIndicatorsAndExitRules() {
    // Minervini's live shape: sma50 is the deepest, and it lives in the INDICATORS list.
    StrategyDefinition minervini =
        definition(
            List.of(
                indicator("px", Map.of("period", 1)),
                indicator("sma20", Map.of("period", 20)),
                indicator("sma50", Map.of("period", 50))),
            List.of(
                new StrategyDefinition.ExitRuleSpec("stop_loss", Map.of("basis", "percent", "value", 8)),
                new StrategyDefinition.ExitRuleSpec(
                    "trailing_stop", Map.of("basis", "indicator", "alias", "sma50"))));
    assertThat(SwingCoverageProbe.lookbackBars(minervini)).isEqualTo(50);

    // Manas's live shape: the deepest window lives ONLY in the EXIT RULES (atr_period 20) — invisible
    // to the indicator bank, which is why exit rules must be scanned too.
    StrategyDefinition manas =
        definition(
            List.of(indicator("px", Map.of("period", 1))),
            List.of(
                new StrategyDefinition.ExitRuleSpec(
                    "stop_loss", Map.of("basis", "atr_multiple", "value", 2, "cap_pct", 10, "atr_period", 20)),
                new StrategyDefinition.ExitRuleSpec(
                    "square_off",
                    Map.of("fast_pct", 35, "fast_bars", 3, "parabolic_ma", 10, "parabolic_dist_pct", 40))));
    assertThat(SwingCoverageProbe.lookbackBars(manas)).isEqualTo(20);
  }

  @Test
  @DisplayName("percentage params are not mistaken for bar depths")
  void percentParamsAreNotDepths() {
    // The reason DEPTH_PARAMS is a whitelist rather than "max over all numeric params": Manas's
    // non-depth params (parabolic_dist_pct 40, fast_pct 35) are numerically LARGER than its true
    // depth of 20. A max-over-all rule would report 40 and would have wrongly flagged Manas as
    // gap-exposed on 2026-08-03, destroying the precision this probe exists to give.
    StrategyDefinition manas =
        definition(
            List.of(),
            List.of(
                new StrategyDefinition.ExitRuleSpec(
                    "square_off",
                    Map.of("fast_pct", 35, "fast_bars", 3, "parabolic_ma", 10, "parabolic_dist_pct", 40))));

    assertThat(SwingCoverageProbe.lookbackBars(manas))
        .as("10 (parabolic_ma), not 40 (parabolic_dist_pct)")
        .isEqualTo(10);
  }

  @Test
  @DisplayName("a strategy declaring no depth reads no window")
  void noDepthMeansNoWindow() {
    assertThat(SwingCoverageProbe.lookbackBars(null)).isZero();
    assertThat(
            SwingCoverageProbe.lookbackBars(
                definition(
                    List.of(),
                    List.of(
                        new StrategyDefinition.ExitRuleSpec(
                            "stop_loss", Map.of("basis", "percent", "value", 8))))))
        .isZero();
  }
}
