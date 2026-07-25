package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.GateNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.BarValues;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ScoreBreakdownJson;
import in.arthayantra.strategyengine.normalize.NormalizeSpec;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The composite rejection row ({@code composite_rejections}, V044):
 * {@code SignalEngine.recordCompositeRejection}.
 *
 * <p>All 38 surviving published scalpers share ONE composite ({@code rsi14} rsi_momentum w1 +
 * {@code supertrend} direction w1 at threshold 0.2 on one 3m NIFTY-future series), so
 * {@code COMPOSITE_BELOW_THRESHOLD} is effectively "RSI did not clear" — and the breakdown the
 * engine used to throw away is exactly the RSI operand distribution the owner cannot otherwise
 * measure. These drive the REAL {@link EntryEvaluator} through that REAL composite (the
 * {@code SignalEngineOutcomeTest} fixture) so the recorded row is produced from market inputs, not
 * from a hand-assembled breakdown.
 *
 * <p>The row lands in its OWN table rather than as a new {@code signal_rejections} rail — see the
 * V044 header for why sharing that table was built and rejected.
 *
 * <p>The load-bearing assertion is the EXCLUSION: {@code CHART_GATE_FAILED} must write NO row. It
 * runs ~3.4x the volume (504 vs 192 measured in a session) and carries no tuning signal, so it is
 * deliberately not recorded — and that is asserted exhaustively over every {@link
 * SignalEngine.Outcome}, so a future outcome cannot start writing rows by accident.
 */
class SignalEngineCompositeRejectionTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final OffsetDateTime BAR =
      OffsetDateTime.of(2026, 7, 17, 14, 30, 0, 0, IST);
  private static final UUID VERSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  // ---------------------------------------------------------------- the live scalper composite

  /** The live scalper shape: gate passes iff close > 100; composite = (rsi + direction)/2 >= 0.2. */
  private static StrategyDefinition definition() {
    return new StrategyDefinition(
        "scalp-composite-rejection-test",
        "1",
        "3m",
        List.of(),
        List.of(
            indicator("rsi14", NormalizeSpec.rsiMomentum()),
            indicator("supertrend", NormalizeSpec.direction())),
        StrategyDefinition.Direction.LONG,
        new GateNode.Expression("close", GateNode.Comparison.GT, null, new BigDecimal("100")),
        new StrategyDefinition.Scoring(
            new BigDecimal("0.2"), new BigDecimal("0.5"), BigDecimal.ZERO),
        List.of(),
        null,
        null);
  }

  private static StrategyDefinition.IndicatorSpec indicator(String alias, NormalizeSpec spec) {
    return new StrategyDefinition.IndicatorSpec(
        alias, alias, "3m", Map.of(), BigDecimal.ONE, false, spec, null);
  }

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

  private static Optional<EntryEvaluator.Evaluation> evaluate(
      BigDecimal rsi, BigDecimal supertrend, BigDecimal close) {
    return EntryEvaluator.evaluate(definition(), bank(rsi, supertrend, close), 0);
  }

  /** ST DOWN + RSI 55 ⇒ composite 0.125 < 0.2 with the gate WIDE OPEN — the recordable outcome. */
  private static Optional<EntryEvaluator.Evaluation> compositeShort() {
    return evaluate(new BigDecimal("55"), new BigDecimal("-1"), new BigDecimal("101"));
  }

  /** Composite-passing inputs with the gate SHUT (close 99 !> 100) — the excluded outcome. */
  private static Optional<EntryEvaluator.Evaluation> gateShut() {
    return evaluate(new BigDecimal("70"), BigDecimal.ONE, new BigDecimal("99"));
  }

  // ---------------------------------------------------------------------------- the fixture

  private record Fixture(SignalEngine engine, CompositeRejectionWriter writer) {}

  private static Fixture fixture(boolean flagOn) {
    CompositeRejectionWriter writer = mock(CompositeRejectionWriter.class);
    FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
    when(resolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(Optional.of(List.of()));
    SignalEngine engine =
        new SignalEngine(
            mock(StrategyRepository.class),
            mock(SignalRepository.class),
            mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class),
            mock(LiveSeriesStore.class),
            resolver,
            mock(RedisConnectionFactory.class),
            new ObjectMapper(),
            Clock.fixed(Instant.ofEpochMilli(2_000L), ZoneOffset.UTC),
            new PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            Optional.empty(),
            Optional.empty(),
            mock(RejectionWriter.class),
            mock(RiskSuppressionWriter.class),
            writer,
            java.util.Optional.empty(),
            mock(PlatformTransactionManager.class),
            60,
            flagOn);
    return new Fixture(engine, writer);
  }

  private static SignalEngine.Loaded loaded() {
    return new SignalEngine.Loaded(
        UUID.randomUUID(), VERSION_ID, "scalp-composite-rejection-test", "Composite Rejection Test",
        "1", "checksum", definition(), List.of(), Set.of(), null, "scalper");
  }

  private static void record(
      Fixture fixture, SignalEngine.Outcome outcome, Optional<EntryEvaluator.Evaluation> evaluation) {
    fixture
        .engine()
        .recordCompositeRejection(
            outcome, loaded(), "NFO", "NIFTY26JULFUT", "3m",
            new EngineCandle(
                BAR, new BigDecimal("101"), new BigDecimal("101"), new BigDecimal("101"),
                new BigDecimal("101"), 100L),
            evaluation);
  }

  // ------------------------------------------------------------------------ the exclusion

  @Test
  void aChartGateFailedEvaluationWritesNoRow() {
    // THE load-bearing exclusion, driven end-to-end from market inputs: the gate said no, so the
    // composite was never consulted and there is no operand distribution to study. ~3.4x the volume
    // (504 vs 192 measured in one session) for zero tuning signal.
    Fixture fixture = fixture(true);
    assertThat(SignalEngine.classifyEntryOutcome(gateShut()))
        .isEqualTo(SignalEngine.Outcome.CHART_GATE_FAILED);

    record(fixture, SignalEngine.Outcome.CHART_GATE_FAILED, gateShut());

    verifyNoInteractions(fixture.writer());
  }

  @Test
  void exactlyOneOutcomeEverWritesARowSoANewOutcomeCannotStartWritingByAccident() {
    for (SignalEngine.Outcome outcome : SignalEngine.Outcome.values()) {
      Fixture fixture = fixture(true);
      // the SAME recordable evaluation for every outcome — only the outcome differs, so a row that
      // appears for anything but COMPOSITE_BELOW_THRESHOLD is the gating being wrong, not the data
      record(fixture, outcome, compositeShort());

      if (outcome == SignalEngine.Outcome.COMPOSITE_BELOW_THRESHOLD) {
        verify(fixture.writer())
            .record(
                any(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), any());
      } else {
        verifyNoInteractions(fixture.writer());
      }
    }
  }

  @Test
  void anUnscoreableEvaluationIsNeverRecordedEvenIfTheOutcomeSaysComposite() {
    // Defensive totality: the outcome cannot co-occur with an empty evaluation, but the method must
    // never throw onto the sole signal-eval thread if it ever did.
    Fixture fixture = fixture(true);
    record(fixture, SignalEngine.Outcome.COMPOSITE_BELOW_THRESHOLD, Optional.empty());
    verifyNoInteractions(fixture.writer());
  }

  @Test
  void theKillSwitchStopsTheWriteWithoutTouchingTheOutcome() {
    Fixture fixture = fixture(false);
    record(fixture, SignalEngine.Outcome.COMPOSITE_BELOW_THRESHOLD, compositeShort());
    verifyNoInteractions(fixture.writer());
  }

  // -------------------------------------------------------------------------- the payload

  @Test
  void theRecordedRowCarriesTheNormalizedCompositeThresholdAndSignedMargin() {
    Fixture fixture = fixture(true);
    record(fixture, SignalEngine.Outcome.COMPOSITE_BELOW_THRESHOLD, compositeShort());

    verify(fixture.writer())
        .record(
            eq(VERSION_ID),
            eq("scalp-composite-rejection-test"),
            eq("NFO"),
            eq("NIFTY26JULFUT"),
            eq("3m"),
            // CompositeScorer divides at scale 8 (raw `0.12500000`); the columns carry the SAME
            // canonicalization as the persisted breakdown, so the row cannot disagree with itself
            eq(new BigDecimal("0.125")),
            eq(new BigDecimal("0.2")), // entry_rules.scoring.threshold
            eq(new BigDecimal("-0.075")), // margin = composite - threshold, signed
            anyString(),
            eq(BAR));
  }

  @Test
  void theScoreBreakdownCarriesPerFactorContributionSoRsiVsSupertrendIsRecoverable()
      throws Exception {
    // The persisted score_breakdown IS the frozen canonical ScoreBreakdown — no envelope, because
    // the table itself identifies the stage. THE PAYOFF: the RSI operand and its per-factor
    // contribution, separable from supertrend's.
    JsonNode breakdown =
        new ObjectMapper()
            .readTree(ScoreBreakdownJson.write(compositeShort().orElseThrow().breakdown()));

    assertThat(breakdown.path("composite").asText()).isEqualTo("0.125");
    assertThat(breakdown.path("threshold").asText()).isEqualTo("0.2");
    assertThat(breakdown.path("passed").asBoolean()).isFalse();
    // the gate is present and OPEN — this row is a composite verdict, not a gate verdict
    assertThat(breakdown.path("gate").path("passed").asBoolean()).isTrue();

    Map<String, JsonNode> byAlias = new java.util.LinkedHashMap<>();
    breakdown.path("indicators").forEach(n -> byAlias.put(n.path("alias").asText(), n));
    assertThat(byAlias).containsOnlyKeys("rsi14", "supertrend");
    assertThat(byAlias.get("rsi14").path("rawValue").asText()).isEqualTo("55"); // the RSI operand
    assertThat(byAlias.get("rsi14").path("score").asText()).isEqualTo("0.25");
    assertThat(byAlias.get("rsi14").path("weight").asText()).isEqualTo("1");
    assertThat(byAlias.get("rsi14").path("contribution").asText()).isEqualTo("0.25");
    assertThat(byAlias.get("supertrend").path("rawValue").asText()).isEqualTo("-1");
    assertThat(byAlias.get("supertrend").path("score").asText()).isEqualTo("0");
  }

  @Test
  void theScoreBreakdownPayloadStaysSmallEnoughForTheRowRate() {
    // Retention sizing: ~192 of these a session against the live composite. Printed so the figure in
    // the V044 retention note is measured, not guessed.
    int bytes =
        ScoreBreakdownJson.write(compositeShort().orElseThrow().breakdown())
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            .length;
    System.out.println("composite_rejections score_breakdown = " + bytes + " bytes");
    assertThat(bytes).isLessThan(4_096);
  }
}
