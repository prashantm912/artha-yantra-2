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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  @DisplayName("exit depth takes the max across indicators AND exit rules")
  void exitDepthSpansIndicatorsAndExitRules() {
    // NOTE: real-config assertions live in SwingCoverageDepthRatchetTest, which reads the seeded
    // YAMLs. These fixtures test the ARITHMETIC only and are deliberately not labelled as any live
    // strategy's shape — the deleted version of this test claimed to be "Manas's live shape" while
    // omitting the sma50 both Manas YAMLs declare, and asserted 20 where the truth is 50.
    StrategyDefinition depthInExitRuleOnly =
        definition(
            List.of(indicator("px", Map.of("period", 1))),
            List.of(
                new StrategyDefinition.ExitRuleSpec(
                    "stop_loss", Map.of("basis", "atr_multiple", "value", 2, "cap_pct", 10, "atr_period", 20))));
    assertThat(SwingCoverageProbe.exitLookbackBars(depthInExitRuleOnly, null))
        .as("an exit-rule depth is invisible to the bank, so it must be scanned")
        .isEqualTo(20);
  }

  @Test
  @DisplayName("percentage params are not mistaken for bar depths")
  void percentParamsAreNotDepths() {
    // Why DEPTH_PARAMS is a whitelist rather than "max over all numeric params": the non-depth
    // params sit in the same map and are numerically LARGER than the real depth.
    StrategyDefinition d =
        definition(
            List.of(),
            List.of(
                new StrategyDefinition.ExitRuleSpec(
                    "square_off",
                    Map.of("fast_pct", 35, "fast_bars", 3, "parabolic_ma", 10, "parabolic_dist_pct", 40))));

    assertThat(SwingCoverageProbe.exitLookbackBars(d, null))
        .as("10 (parabolic_ma), not 40 (parabolic_dist_pct)")
        .isEqualTo(10);
  }

  @Test
  @DisplayName("entry depth ignores exit rules entirely")
  void entryDepthIgnoresExitRules() {
    // The Major: sma50 reachable ONLY as a trailing_stop basis must not widen the ENTRY window.
    StrategyDefinition d =
        definition(
            List.of(indicator("px", Map.of("period", 1)), indicator("sma50", Map.of("period", 50))),
            List.of(
                new StrategyDefinition.ExitRuleSpec(
                    "trailing_stop", Map.of("basis", "indicator", "alias", "sma50"))));

    assertThat(SwingCoverageProbe.entryLookbackBars(d))
        .as("sma50 is exit-only and unscored — the entry gate never reads it")
        .isZero();
    assertThat(SwingCoverageProbe.exitLookbackBars(d, null)).isEqualTo(50);
  }

  @Test
  @DisplayName("MATERIALITY: one missing session at depth 20 refuses, with a tested boundary")
  void depth20CatchesOneSessionAndDepth50KeepsTheBoundary() {
    LocalDate end = LocalDate.of(2026, 8, 3);

    // ⚠️ EVERY series here is LONGER than the depth it is probed at, because that is the ONLY shape
    // production presents: SwingBatchEngine drops any symbol under doctrine.minBars() (60), then
    // probe() slices the last `lookbackBars`. windowSessions is dates.size() + missing.size(), so a
    // long series probed at depth 20 with one hole yields 21 — NOT 20.
    //
    // The first cut of this test built exactly 20 sessions and DELETED one, handing the probe 19
    // bars and windowSessions 20. It asserted windowSessions()==20 and went green, so it looked
    // rigorously pinned — to a population that cannot occur. Under it, MATERIALITY_DENOMINATOR=21
    // passed while production computed 1*21 > 21 = false and refused nothing. Caught by review, not
    // by this suite. Pinning the population is worth nothing if it is pinned to the wrong one.
    SwingCoverageProbe.Coverage complete20 =
        SwingCoverageProbe.probe(tradingWindow(60, end), 20, NSE);
    assertThat(complete20.windowSessions()).isEqualTo(20);
    assertThat(complete20.materiallyIncomplete())
        .as("zero missing sessions is the largest gap that passes at depth 20")
        .isFalse();

    // A hole at index 50 of 60 sits INSIDE the last 20 bars, so it lands in the probed slice.
    SwingCoverageProbe.Coverage oneMissing20 =
        SwingCoverageProbe.probe(tradingWindow(60, end, 50), 20, NSE);
    assertThat(oneMissing20.windowSessions())
        .as("20 present bars spanning 21 trading sessions — the production shape")
        .isEqualTo(21);
    assertThat(oneMissing20.missing()).hasSize(1);
    assertThat(oneMissing20.materiallyIncomplete())
        .as("one missing session is 1/21 = 4.76%, above the fixed 1/22 = 4.545% band")
        .isTrue();

    SwingCoverageProbe.Coverage twoMissing50 =
        SwingCoverageProbe.probe(tradingWindow(100, end, 60, 80), 50, NSE);
    assertThat(twoMissing50.windowSessions()).isEqualTo(52);
    assertThat(twoMissing50.missing()).hasSize(2);
    assertThat(twoMissing50.materiallyIncomplete())
        .as("two missing sessions are 2/52 = 3.85%, the largest depth-50 gap that passes")
        .isFalse();

    SwingCoverageProbe.Coverage threeMissing50 =
        SwingCoverageProbe.probe(tradingWindow(100, end, 60, 70, 80), 50, NSE);
    assertThat(threeMissing50.windowSessions()).isEqualTo(53);
    assertThat(threeMissing50.missing()).hasSize(3);
    assertThat(threeMissing50.materiallyIncomplete())
        .as("three missing sessions are 3/53 = 5.66%, the smallest depth-50 gap that refuses")
        .isTrue();
  }

  @Test
  @DisplayName("MATERIALITY: the fixed fraction distinguishes shallow and deep windows")
  void materialityUsesWindowFraction() {
    // The Critical. The SAME 5-session hole is material inside a 50-bar window and immaterial inside
    // a 252-bar one. Fractions are window-local — m/(L+m), NOT m/L — so this is 5/55 = 9.09% against
    // 5/257 = 1.95%, either side of the fixed 1/22 = 4.545% band.
    List<EngineCandle> bars =
        series(LocalDate.of(2025, 3, 1), LocalDate.of(2026, 8, 3),
            LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16),
            LocalDate.of(2026, 6, 18), LocalDate.of(2026, 6, 19));

    SwingCoverageProbe.Coverage shallow = SwingCoverageProbe.probe(bars, 50, NSE);
    SwingCoverageProbe.Coverage deep = SwingCoverageProbe.probe(bars, 252, NSE);

    assertThat(shallow.missing()).hasSize(5);
    assertThat(shallow.materiallyIncomplete()).as("5 of 55 window sessions = 9.09%, over the 4.545% band — refuse").isTrue();

    assertThat(deep.missing()).hasSize(5);
    assertThat(deep.incomplete()).as("the hole is still SEEN at depth 252").isTrue();
    assertThat(deep.materiallyIncomplete())
        .as("5 of 257 window sessions = 1.95%, under the band — must NOT refuse, or primary-base stops entering nightly")
        .isFalse();
  }

  @Test
  @DisplayName("FAIL-CLOSED: an undeterminable probe blocks entry, though it is not 'incomplete'")
  void undeterminableCoverageBlocksEntry() {
    // The shipped defect (cross-vendor review 2026-08-10): undeterminable() carries an EMPTY missing
    // list, so incomplete() is false and materiallyIncomplete() is false with it. Both entry gates
    // keyed on materiallyIncomplete(), so a probe that FAILED — exception, a year outside the
    // bundled calendar, an invalid bar, depth degraded to zero — permitted the entry it exists to
    // guard. The gate reported as a gate while passing everything through.
    SwingCoverageProbe.Coverage unknown = SwingCoverageProbe.undeterminable(50);

    assertThat(unknown.incomplete())
        .as("no claim is not a claim of incompleteness — this stays false BY DESIGN")
        .isFalse();
    assertThat(unknown.materiallyIncomplete())
        .as("and so does this — which is exactly why an entry must not key on it")
        .isFalse();
    assertThat(unknown.notProvenSound())
        .as("but an entry MUST refuse: an unreadable window is not a safe window")
        .isTrue();
  }

  @Test
  @DisplayName("FAIL-CLOSED: a complete window still permits entry")
  void completeCoverageDoesNotBlockEntry() {
    // The other half — notProvenSound() must not become a blanket refusal, or the batch enters nothing.
    SwingCoverageProbe.Coverage complete =
        SwingCoverageProbe.probe(tradingWindow(80, LocalDate.of(2026, 8, 3)), 50, NSE);

    assertThat(complete.missing()).isEmpty();
    assertThat(complete.notProvenSound()).isFalse();
  }

  @Test
  @DisplayName("a strategy declaring no depth reads no window")
  void noDepthMeansNoWindow() {
    assertThat(SwingCoverageProbe.entryLookbackBars(null)).isZero();
    assertThat(SwingCoverageProbe.exitLookbackBars(null, null)).isZero();
    assertThat(
            SwingCoverageProbe.exitLookbackBars(
                definition(
                    List.of(),
                    List.of(
                        new StrategyDefinition.ExitRuleSpec(
                            "stop_loss", Map.of("basis", "percent", "value", 8)))),
                null))
        .isZero();
  }

  /** A fixed count of NSE sessions ending at {@code end}, optionally omitting session indexes. */
  private static List<EngineCandle> tradingWindow(
      int sessionCount, LocalDate end, int... missingIndexes) {
    List<LocalDate> sessions = new ArrayList<>();
    for (LocalDate d = end; sessions.size() < sessionCount; d = d.minusDays(1)) {
      if (NSE.isTradingDay(d)) {
        sessions.add(0, d);
      }
    }
    Set<LocalDate> missing = new HashSet<>();
    for (int index : missingIndexes) {
      missing.add(sessions.get(index));
    }
    return sessions.stream()
        .filter(d -> !missing.contains(d))
        .map(
            d ->
                new EngineCandle(
                    d.atStartOfDay().atOffset(IST), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ONE, 1L))
        .toList();
  }
}
