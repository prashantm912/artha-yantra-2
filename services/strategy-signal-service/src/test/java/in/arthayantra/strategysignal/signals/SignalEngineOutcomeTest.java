package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.normalize.NormalizeSpec;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Chip task_37ee83e0: the engine used to write NOTHING on a chart-stage no-entry, so four states
 * shared one DB signature (zero rejection rows) — engine dead / chart gate closed / indicators
 * warming / composite below threshold. These drive the REAL {@link EntryEvaluator} through the
 * REAL live scalper composite (every published+enabled scalper groups to a SINGLE distinct
 * composite, {@code rsi14} rsi_momentum w1 + {@code supertrend} direction w1 at
 * {@code entry_rules.scoring.threshold: 0.2}, on one 3m NIFTY-future series) and assert that each
 * of those states now classifies to a DISTINCT outcome.
 *
 * <p>The scalper COUNT has moved twice — 63 when this was written (2026-07-17), 38 after the owner
 * disabled 25 of them (all BTST, all straddle, all {@code -sensex-sensexoi}) on 2026-07-20. Only the
 * shared-composite fact is load-bearing here, so the count is deliberately no longer stated inline
 * where it would go stale again.
 */
class SignalEngineOutcomeTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /** The live scalper shape: gate on close, composite = (rsi_momentum + direction) / 2 >= 0.2. */
  private static StrategyDefinition definition() {
    return new StrategyDefinition(
        "scalp-outcome-test",
        "1",
        "3m",
        List.of(),
        List.of(
            indicator("rsi14", NormalizeSpec.rsiMomentum()),
            indicator("supertrend", NormalizeSpec.direction())),
        StrategyDefinition.Direction.LONG,
        // stands in for the chart gate: passes iff close > 100
        new GateNode.Expression("close", GateNode.Comparison.GT, null, new BigDecimal("100")),
        new StrategyDefinition.Scoring(
            new BigDecimal("0.2"), new BigDecimal("0.5"), BigDecimal.ZERO),
        List.of(),
        null,
        null);
  }

  private static StrategyDefinition.IndicatorSpec indicator(String alias, NormalizeSpec normalize) {
    return new StrategyDefinition.IndicatorSpec(
        alias, alias, "3m", Map.of(), BigDecimal.ONE, false, normalize, null);
  }

  /** rsi/supertrend null ⇒ still warming; close drives the gate. */
  private static BarValues bank(BigDecimal rsi, BigDecimal supertrend, BigDecimal close) {
    return new BarValues() {
      @Override
      public BigDecimal valueAt(String alias, int i) {
        return "rsi14".equals(alias) ? rsi : supertrend;
      }

      @Override
      public BigDecimal previousValueAt(String alias, int i) {
        return valueAt(alias, i);
      }

      @Override
      public BigDecimal builtin(String name, int i) {
        return close;
      }

      @Override
      public boolean has(String alias) {
        return true;
      }
    };
  }

  private static SignalEngine.Outcome classify(
      BigDecimal rsi, BigDecimal supertrend, BigDecimal close) {
    Optional<EntryEvaluator.Evaluation> evaluation =
        EntryEvaluator.evaluate(definition(), bank(rsi, supertrend, close), 0);
    return SignalEngine.classifyEntryOutcome(evaluation);
  }

  @Test
  void theSuperTrendDownLegThatCostAFalseStarvationAlarmIsNowItsOwnOutcome() {
    // 2026-07-17: ST DOWN (direction ⇒ 0) + RSI < 58 ⇒ composite = rsi_score/2 < 0.2. The chart
    // gate is WIDE OPEN — this is a healthy engine saying a normal "no", and it must not read the
    // same as a dead one. RSI 55 ⇒ (55-50)/20 = 0.25 ⇒ composite 0.125 < 0.2.
    assertThat(classify(new BigDecimal("55"), new BigDecimal("-1"), new BigDecimal("101")))
        .isEqualTo(SignalEngine.Outcome.COMPOSITE_BELOW_THRESHOLD);
  }

  @Test
  void aClosedChartGateIsDistinctFromABelowThresholdComposite() {
    // Same composite-passing inputs, gate shut (close 99 !> 100): the cause reported is the GATE.
    // RSI 70 ⇒ 1.0, ST UP ⇒ 1.0 ⇒ composite 1.0 >= 0.2, so ONLY the gate is saying no.
    assertThat(classify(new BigDecimal("70"), BigDecimal.ONE, new BigDecimal("99")))
        .isEqualTo(SignalEngine.Outcome.CHART_GATE_FAILED);
  }

  @Test
  void aGateAndCompositeBothFailingReportsTheGateBecauseThatIsTheOrderTheEvaluatorApplies() {
    assertThat(classify(new BigDecimal("55"), new BigDecimal("-1"), new BigDecimal("99")))
        .isEqualTo(SignalEngine.Outcome.CHART_GATE_FAILED);
  }

  @Test
  void aRequiredIndicatorStillWarmingIsUnscoreableRatherThanAQuietNo() {
    // CompositeScorer returns empty on a null REQUIRED participant — never a silent zero. The gate
    // would have passed, which is exactly why this must not collapse into CHART_GATE_FAILED.
    assertThat(classify(null, BigDecimal.ONE, new BigDecimal("101")))
        .isEqualTo(SignalEngine.Outcome.UNSCOREABLE_INDICATORS_WARMING);
    assertThat(classify(new BigDecimal("70"), null, new BigDecimal("101")))
        .isEqualTo(SignalEngine.Outcome.UNSCOREABLE_INDICATORS_WARMING);
  }

  @Test
  void gateOpenAndCompositeAtThresholdFires() {
    // RSI 58 ⇒ (58-50)/20 = 0.4; ST DOWN ⇒ 0 ⇒ composite 0.2 == threshold ⇒ entry (>=, not >).
    assertThat(classify(new BigDecimal("58"), new BigDecimal("-1"), new BigDecimal("101")))
        .isEqualTo(SignalEngine.Outcome.FIRED);
    assertThat(classify(new BigDecimal("70"), BigDecimal.ONE, new BigDecimal("101")))
        .isEqualTo(SignalEngine.Outcome.FIRED);
  }

  @Test
  void everyOutcomeTagIsDistinctSoTheFourStatesCanNeverShareASignatureAgain() {
    assertThat(SignalEngine.Outcome.values())
        .extracting(Enum::name)
        .containsExactlyInAnyOrder(
            "FIRED",
            "CONFLUENCE_BLOCKED",
            "CONFLUENCE_GATE_ABSENT",
            "DISCIPLINE_PAUSED",
            "COMPOSITE_BELOW_THRESHOLD",
            "CHART_GATE_FAILED",
            "UNSCOREABLE_INDICATORS_WARMING");
  }

  @Test
  void anUnwarmedSeriesWarnsOncePerSessionNotOncePerStrategyPerBar() {
    // Every published+enabled scalper resolves to ONE 3m NIFTY-future series, so the fault is
    // per-SERIES: without dedup an unwarmed series emits one identical WARN per scalper every bar
    // (~38 as of 2026-07-20, and it was 63 three days earlier — the dedup must not depend on it).
    Map<String, LocalDate> seen = new HashMap<>();
    LocalDate session = LocalDate.of(2026, 7, 17);
    String nifty = "NFO:NIFTY26JULFUT:3m";

    assertThat(SignalEngine.firstUnscoreableForSeries(seen, nifty, session)).isTrue();
    // every later strategy/bar on the SAME series in the SAME session stays silent
    for (int i = 0; i < 63; i++) {
      assertThat(SignalEngine.firstUnscoreableForSeries(seen, nifty, session)).isFalse();
    }
    // a DIFFERENT series is its own fault and warns on its own
    assertThat(SignalEngine.firstUnscoreableForSeries(seen, "BFO:SENSEX26JULFUT:3m", session))
        .isTrue();
    // the next session re-arms — a fault that persists across days must be reported each day
    assertThat(SignalEngine.firstUnscoreableForSeries(seen, nifty, session.plusDays(1))).isTrue();
    assertThat(SignalEngine.firstUnscoreableForSeries(seen, nifty, session.plusDays(1))).isFalse();
    // bounded: one entry per series, never per strategy or per bar
    assertThat(seen).hasSize(2);
  }

  @Test
  void warmupGraceKeepsAColdStartedSeriesQuietUntilTenIstThenWarns() {
    // 09:15 open + the cold-start fill window: no WARN. Past 10:00 IST a required indicator that is
    // STILL null means the REST warm never delivered — a fault, not market behaviour.
    assertThat(SignalEngine.pastWarmupGrace(OffsetDateTime.of(2026, 7, 17, 9, 15, 0, 0, IST)))
        .isFalse();
    assertThat(SignalEngine.pastWarmupGrace(OffsetDateTime.of(2026, 7, 17, 9, 59, 0, 0, IST)))
        .isFalse();
    assertThat(SignalEngine.pastWarmupGrace(OffsetDateTime.of(2026, 7, 17, 10, 0, 0, 0, IST)))
        .isTrue();
    assertThat(SignalEngine.pastWarmupGrace(OffsetDateTime.of(2026, 7, 17, 14, 30, 0, 0, IST)))
        .isTrue();
    // a UTC-offset bar is converted, not read raw: 09:00 UTC == 14:30 IST ⇒ past the grace
    assertThat(SignalEngine.pastWarmupGrace(OffsetDateTime.of(2026, 7, 17, 9, 0, 0, 0, ZoneOffset.UTC)))
        .isTrue();
    // 04:00 UTC == 09:30 IST ⇒ still inside the grace
    assertThat(SignalEngine.pastWarmupGrace(OffsetDateTime.of(2026, 7, 17, 4, 0, 0, 0, ZoneOffset.UTC)))
        .isFalse();
  }
}
