package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import in.arthayantra.strategyengine.config.GateNode;
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

  /** Same, but with a gate that actually READS {@code gateAlias} — entry scoping keys on this. */
  private static StrategyDefinition gated(
      List<StrategyDefinition.IndicatorSpec> indicators, String gateAlias) {
    return new StrategyDefinition(
        "t", "1", "1d", List.of(), indicators, StrategyDefinition.Direction.LONG,
        new GateNode.Expression(gateAlias, GateNode.Comparison.GT, null, BigDecimal.ZERO), null,
        List.of(), null, null);
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

  @Test
  @DisplayName("FOOTPRINT: a hole just past the declared depth is invisible to probe and refused by probeEntry")
  void probeEntryFindsHolesInTheSlackRegionThatProbeCannotSee() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    // Session index 39 of 60 is the one position that separates the two windows: the last 20 bars
    // span sessions 40..59 and never touch it, while the last 22 bars reach back to session 37 and
    // do. That is the entire footprint gap, in one fixture.
    List<EngineCandle> bars = tradingWindow(60, end, 39);

    SwingCoverageProbe.Coverage declaredOnly = SwingCoverageProbe.probe(bars, 20, NSE);
    assertThat(declaredOnly.missing())
        .as("the declared 20-bar window spans no hole — this is what production used to see")
        .isEmpty();
    assertThat(declaredOnly.materiallyIncomplete()).isFalse();

    SwingCoverageProbe.Coverage entry = SwingCoverageProbe.probeEntry(bars, 20, NSE);
    assertThat(entry.missing())
        .as("reading DEPTH_SLACK bars further back reaches the hole")
        .hasSize(1);
    assertThat(entry.materialityBasis())
        .as("the hole is OUTSIDE the declared depth, so it does not inflate the denominator")
        .isEqualTo(20);
    assertThat(entry.windowSessions())
        .as("the footprint DID widen — without this the test passes vacuously at DEPTH_SLACK 0")
        .isGreaterThan(declaredOnly.windowSessions());
    assertThat(entry.materiallyIncomplete())
        .as("1 hole against a declared-depth basis of 20 is 5%, over the 4.545% band — refuse")
        .isTrue();
  }

  @Test
  @DisplayName("FOOTPRINT: widening the read moves no calibration point — slack tightens or does nothing")
  void slackWidensTheFootprintWithoutMovingTheMaterialityBand() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    // The invariant that makes DEPTH_SLACK safe to be non-zero again. At 2 it once LOOSENED this
    // gate, because materiality then divided by the probed span: widening the probe widened the
    // denominator and 1 x 22 > 23 went false, silently undoing the 21 -> 22 recalibration. Every
    // calibration point from depth20CatchesOneSessionAndDepth50KeepsTheBoundary is re-run through
    // the production entry call and must return the SAME verdict.
    record Case(String label, List<EngineCandle> bars, int depth, boolean refuses) {}
    List<Case> cases =
        List.of(
            new Case("depth 20, no hole", tradingWindow(60, end), 20, false),
            new Case("depth 20, one hole", tradingWindow(60, end, 50), 20, true),
            new Case("depth 50, two holes", tradingWindow(100, end, 60, 80), 50, false),
            new Case("depth 50, three holes", tradingWindow(100, end, 60, 70, 80), 50, true));

    for (Case c : cases) {
      SwingCoverageProbe.Coverage declaredOnly = SwingCoverageProbe.probe(c.bars(), c.depth(), NSE);
      SwingCoverageProbe.Coverage entry = SwingCoverageProbe.probeEntry(c.bars(), c.depth(), NSE);

      assertThat(entry.materiallyIncomplete())
          .as("%s: the entry probe must agree with the calibrated band", c.label())
          .isEqualTo(c.refuses())
          .isEqualTo(declaredOnly.materiallyIncomplete());
      assertThat(entry.windowSessions())
          .as(
              "%s: the entry probe must actually READ further than the declared depth, else this"
                  + " whole test is vacuous",
              c.label())
          .isGreaterThan(declaredOnly.windowSessions());
      assertThat(entry.lookbackBars())
          .as("%s: the reading reports the DECLARED depth, not the widened one", c.label())
          .isEqualTo(c.depth());
    }
  }

  @Test
  @DisplayName("FOOTPRINT: the entry probe is driven by the production depth, not a hand-passed one")
  void probeEntryTakesItsDepthFromTheStrategyDefinition() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    // Every other test in this class hands probe() a literal depth, which is exactly how a
    // mis-scoped entryLookbackBars would go unnoticed. This one goes through the production pair:
    // entryLookbackBars(definition) -> probeEntry, the same two calls SwingBatchEngine#entryCoverage
    // makes.
    StrategyDefinition d =
        gated(
            List.of(indicator("px", Map.of("period", 1)), indicator("sma20", Map.of("period", 20))),
            "sma20");
    int depth = SwingCoverageProbe.entryLookbackBars(d);
    assertThat(depth).as("declared, un-widened — the slack belongs to probeEntry").isEqualTo(20);

    SwingCoverageProbe.Coverage clean =
        SwingCoverageProbe.probeEntry(tradingWindow(60, end), depth, NSE);
    assertThat(clean.materiallyIncomplete()).isFalse();
    assertThat(clean.windowSessions())
        .as("22 bars read for a declared depth of 20")
        .isEqualTo(depth + SwingCoverageProbe.DEPTH_SLACK);

    SwingCoverageProbe.Coverage holed =
        SwingCoverageProbe.probeEntry(tradingWindow(60, end, 50), depth, NSE);
    assertThat(holed.materiallyIncomplete())
        .as("one hole inside a depth-20 entry window refuses, end to end")
        .isTrue();
  }

  @Test
  @DisplayName("FOOTPRINT: a non-positive declared depth makes no claim rather than probing the slack")
  void probeEntryWithNoDepthMakesNoClaim() {
    // entryLookbackBars returns 0 when nothing depth-bearing is read. Without the guard, adding
    // DEPTH_SLACK to 0 would probe a 2-bar window and report it complete — a gate that answers
    // "sound" for a strategy it never measured.
    SwingCoverageProbe.Coverage none =
        SwingCoverageProbe.probeEntry(tradingWindow(60, LocalDate.of(2026, 8, 3)), 0, NSE);
    assertThat(none.determinable()).isFalse();
    assertThat(none.notProvenSound()).isTrue();
  }

  @Test
  @DisplayName("FOOTPRINT: describe() is byte-identical on the exit path and only widens for entry")
  void describeKeepsTheLegacyWordingWhereverTheBasisEqualsTheFootprint() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    // Cross-vendor review Major, caught after the split had already been claimed exit-safe: this
    // string reaches the exit error log AND the operator alert (SwingBatchEngine:974, :984), so a
    // reworded describe() is an exit-path behaviour change however pure the arithmetic is. Pinned as
    // a literal because "the exit reading is unchanged" is exactly the claim that was wrong.
    SwingCoverageProbe.Coverage exit = SwingCoverageProbe.probe(tradingWindow(60, end, 50), 20, NSE);
    assertThat(exit.materialityBasis()).isEqualTo(exit.windowSessions());
    assertThat(exit.describe())
        .as("plain probe = every exit reading: legacy wording, no declared-depth span")
        .isEqualTo("1 of 21 sessions missing (4% of the probed span, declared depth 20,"
            + " 2026-07-21..2026-07-21)");

    SwingCoverageProbe.Coverage entry =
        SwingCoverageProbe.probeEntry(tradingWindow(60, end, 50), 20, NSE);
    assertThat(entry.materialityBasis()).isNotEqualTo(entry.windowSessions());
    assertThat(entry.describe())
        .as("a WIDENED reading names the denominator, because there it differs from the footprint")
        .contains("% of the declared-depth span " + entry.materialityBasis())
        .doesNotContain("% of the probed span");
  }

  // ---------------------------------------------------------------------------------------------
  // probeExit: an OPERAND-AWARE footprint (cross-vendor review, 2026-08-21 and 2026-08-24)
  // ---------------------------------------------------------------------------------------------

  /**
   * ⚠️ REAL seeded resources, never hand-built fixtures. A hand-built "Manas" fixture is exactly what
   * {@code SwingCoverageDepthRatchetTest} warns about, and the first cut of these tests made that
   * mistake: {@code manasShape()} declared no indicators, so its depth read 20 instead of the live 50
   * and the union-vs-sum defect below was invisible.
   */
  private static StrategyDefinition seeded(String resource) throws IOException {
    try (InputStream stream = SwingCoverageProbeTest.class.getResourceAsStream(resource)) {
      assertThat(stream).as("seeded resource %s must exist", resource).isNotNull();
      return StrategyCompiler.compile(
          StrategyDocuments.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
              .config());
    }
  }

  private static StrategyDefinition manas() throws IOException {
    return seeded("/manas-arora-strategies/manas-arora-vcp.yaml");
  }

  private static StrategyDefinition minervini() throws IOException {
    return seeded("/minervini-strategies/minervini-vcp.yaml");
  }

  /**
   * THE DISCRIMINATING PAIR. Identical hold, opposite requirement — the held span belongs in the
   * footprint only when an exit operand actually reads from the entry bar.
   */
  @Test
  @DisplayName("the held span widens Manas and must NOT widen Minervini")
  void footprintIsOperandAware() throws IOException {
    assertThat(SwingCoverageProbe.readsFromEntryBar(manas())).isTrue();
    assertThat(SwingCoverageProbe.readsFromEntryBar(minervini()))
        .as("basis:indicator discards the peak-since-entry, so nothing reads the hold")
        .isFalse();

    int minerviniDeclared = SwingCoverageProbe.exitLookbackBars(minervini(), null);
    assertThat(SwingCoverageProbe.exitFootprintBars(minervini(), null, 200))
        .as("a 200-bar hold must not widen a current-bar-only exit — that is a false ARMED page")
        .isEqualTo(minerviniDeclared);
  }

  /**
   * The two windows OVERLAP (both end at the current bar), so the footprint is their UNION. Summing
   * them probes history no operand reads — the first cut returned {@code heldBars + 89} on live Manas
   * where the requirement is {@code max(50, heldBars + 59)}, over-probing by 30 bars.
   */
  @Test
  @DisplayName("the footprint UNIONS the current- and entry-anchored windows, never sums them")
  void footprintUnionsRatherThanSums() throws IOException {
    StrategyDefinition manas = manas();
    int declared = SwingCoverageProbe.exitLookbackBars(manas, null);
    assertThat(declared).as("live Manas declares an unused sma50 — the ratchet pins this").isEqualTo(50);

    int decay = SwingCoverageProbe.atrDecayLength(20);
    assertThat(decay).isEqualTo(59);

    // Short hold: the current-anchored 50-bar window still dominates.
    assertThat(SwingCoverageProbe.exitFootprintBars(manas, null, 5))
        .as("a 5-bar hold cannot need more than the declared window")
        .isEqualTo(Math.max(declared, 5 + 1 + decay));

    // Long hold: entry-anchored reach dominates, but only by the UNION, not the sum.
    assertThat(SwingCoverageProbe.exitFootprintBars(manas, null, 100))
        .as("union = max(50, 100+1+59) = 160, NOT 50+100+59")
        .isEqualTo(160);
  }

  /**
   * {@code time_stop} was missed by the first cut: it carries no {@code basis} param, and the {@code
   * max_bars} branch looks like pure index arithmetic. Both forms depend on the hold span — {@code
   * max_bars} counts {@code index - entryIndex}, and {@code max_holding_days} scans {@code
   * entryIndex + 1..index} reading each candle ({@code ExitEvaluator:692-705}).
   */
  @Test
  @DisplayName("both time_stop forms count as entry-anchored")
  void timeStopIsHoldSpanDependent() {
    StrategyDefinition maxBars =
        definition(
            List.of(),
            List.of(new StrategyDefinition.ExitRuleSpec("time_stop", Map.of("max_bars", 30))));
    StrategyDefinition maxDays =
        definition(
            List.of(),
            List.of(
                new StrategyDefinition.ExitRuleSpec("time_stop", Map.of("max_holding_days", 30))));

    assertThat(SwingCoverageProbe.readsFromEntryBar(maxBars)).isTrue();
    assertThat(SwingCoverageProbe.readsFromEntryBar(maxDays)).isTrue();
    assertThat(SwingCoverageProbe.exitFootprintBars(maxBars, null, 80))
        .as("a hole delays a max_bars stop, so the hold span must be probed")
        .isEqualTo(81);
    assertThat(SwingCoverageProbe.entryReachBars(
            new StrategyDefinition.ExitRuleSpec("time_stop", Map.of("max_bars", 30))))
        .as("time_stop reads no PRE-entry bar, so its reach is the hold span alone")
        .isZero();
  }

  /**
   * A peak-since-entry trail reads {@code entryIndex..index} and NO pre-entry bar, so its reach is 0 —
   * distinct from an entry-pinned ATR, whose recursive history precedes entry.
   */
  @Test
  @DisplayName("a peak-since-entry trail reaches to entry but not before it")
  void peakTrailReachesEntryOnly() {
    assertThat(SwingCoverageProbe.entryReachBars(
            new StrategyDefinition.ExitRuleSpec(
                "trailing_stop", Map.of("basis", "index_points", "value", 50))))
        .isZero();
    assertThat(SwingCoverageProbe.entryReachBars(
            new StrategyDefinition.ExitRuleSpec(
                "trailing_stop", Map.of("basis", "indicator", "alias", "sma50"))))
        .as("the indicator branch is current-anchored — not entry-anchored at all")
        .isEqualTo(-1);
    assertThat(SwingCoverageProbe.entryReachBars(
            new StrategyDefinition.ExitRuleSpec("stop_loss", Map.of("basis", "percent", "value", 8))))
        .isEqualTo(-1);
  }

  /**
   * The recursive-ATR reach is grounded in a measurement the repo already recorded, not invented:
   * "Wilder retains ~12% of seed influence after 42 bars"
   * ({@code docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md}).
   */
  @Test
  @DisplayName("recursive ATR reach reproduces the recorded 12%-after-42-bars decay")
  void recursiveAtrReachMatchesTheRecordedDecay() {
    assertThat(Math.pow(1.0 - 1.0 / 20, 42))
        .as("the doc's figure, re-derived — guards the formula this constant rests on")
        .isCloseTo(0.12, org.assertj.core.data.Offset.offset(0.01));
    assertThat(SwingCoverageProbe.atrDecayLength(20)).isEqualTo(59);
    assertThat(SwingCoverageProbe.atrDecayLength(14)).isGreaterThan(14);
  }

  /**
   * THE DISCRIMINATING CASE for the widening itself: a hole outside the declared depth but inside the
   * footprint. Without it the widening could be a no-op and every other test here would still pass.
   */
  @Test
  @DisplayName("a hole outside the declared depth but INSIDE the footprint is reported")
  void holeInsideTheFootprintIsReported() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    List<EngineCandle> bars = tradingWindow(100, end, 69); // 30 sessions back from the newest

    assertThat(SwingCoverageProbe.probeExit(bars, 20, 20, NSE).incomplete())
        .as("declared depth alone cannot see a gap 30 rows back — this is the defect")
        .isFalse();
    assertThat(SwingCoverageProbe.probeExit(bars, 20, 50, NSE).incomplete())
        .as("a footprint reaching the entry bar MUST report a gap inside the hold")
        .isTrue();
  }

  /** Widening the footprint may not move the denominator — the one-way property DEPTH_SLACK relies on. */
  @Test
  @DisplayName("a wider footprint does not move the materiality band")
  void widerFootprintDoesNotLoosenTheBand() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    List<EngineCandle> clean = tradingWindow(100, end);

    SwingCoverageProbe.Coverage narrow = SwingCoverageProbe.probeExit(clean, 20, 20, NSE);
    SwingCoverageProbe.Coverage wide = SwingCoverageProbe.probeExit(clean, 20, 50, NSE);

    assertThat(wide.materialityBasis())
        .as("denominator is the DECLARED depth's span, never the widened footprint")
        .isEqualTo(narrow.materialityBasis());
    assertThat(wide.windowSessions()).as("the footprint itself does grow").isGreaterThan(narrow.windowSessions());
    assertThat(wide.lookbackBars()).as("the reading is still ABOUT the declared depth").isEqualTo(20);
  }

  /** A footprint at or below the declared depth reads byte-identically to the pre-fix call. */
  @Test
  @DisplayName("a footprint no wider than the declared depth is the old reading")
  void narrowFootprintIsTheOldReading() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    List<EngineCandle> bars = tradingWindow(60, end, 50);
    assertThat(SwingCoverageProbe.probeExit(bars, 20, 20, NSE)).isEqualTo(SwingCoverageProbe.probe(bars, 20, NSE));
    assertThat(SwingCoverageProbe.probeExit(bars, 20, 0, NSE))
        .as("a footprint SMALLER than the declared depth must not shrink the window")
        .isEqualTo(SwingCoverageProbe.probe(bars, 20, NSE));
  }

  /** Zero declared depth still makes NO claim — it must not become "complete" via a wide footprint. */
  @Test
  @DisplayName("zero declared depth stays undeterminable however wide the footprint")
  void zeroDepthStaysUndeterminable() {
    LocalDate end = LocalDate.of(2026, 8, 3);
    assertThat(SwingCoverageProbe.probeExit(tradingWindow(100, end), 0, 60, NSE).determinable()).isFalse();
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
