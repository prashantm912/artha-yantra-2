package in.arthayantra.strategyengine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The frozen §C-2.6 contract over REAL series, including the A7 context case: a VIX_LEVEL gate
 * plus an RS_VS_INDEX contribution evaluated against a second synthetic series. Asserts the
 * renderer invariant (composite = Σ contributions / weightDenominator) and byte-stable
 * serialization across separately built banks.
 */
class BreakdownContractTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: context-breakdown
      name: "Context Breakdown"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: SIGSYM }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi, timeframe: 1m, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
        - { name: RS_VS_INDEX, alias: rs_idx, timeframe: 1m, params: { lookback: 5 }, weight: 0.5,
            optional: true, instrument: { exchange: NSE, tradingsymbol: "NIFTY 50" },
            normalize: { type: linear, from: -1.0, to: 1.0 } }
        - { name: VIX_LEVEL, alias: vix, timeframe: 1m, weight: 0.0,
            instrument: { exchange: NSE, tradingsymbol: "INDIA VIX" } }
      entry_rules:
        direction: long
        gate:
          all:
            - "vix < 250"
            - "close > 1"
        scoring: { threshold: 0.05, optional_min_score: 0.3, optional_gate_margin: 0.5 }
      exit_rules:
        - { type: time_stop, params: { max_bars: 30 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  private static EngineSeries signalSeries() {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      long closeC = 10000 + 13L * i + (i % 4) * 7L;
      BigDecimal close = BigDecimal.valueOf(closeC, 2);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(2026, 2, 3, 9, 15, 0, 0, IST).plusMinutes(i),
              close, close.add(BigDecimal.valueOf(10, 2)),
              close.subtract(BigDecimal.valueOf(10, 2)), close, 500 + i));
    }
    return EngineSeries.of(new SeriesKey("NSE", "SIGSYM", "1m"), candles);
  }

  private static EngineSeries contextSeries(String symbol, long base) {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      BigDecimal close = BigDecimal.valueOf(base + 5L * i, 2);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(2026, 2, 3, 9, 15, 0, 0, IST).plusMinutes(i),
              close, close, close, close, 0));
    }
    return EngineSeries.of(new SeriesKey("NSE", symbol, "1m"), candles);
  }

  private static IndicatorBank bank(StrategyDefinition definition) {
    EngineSeries signal = signalSeries();
    EngineSeries nifty = contextSeries("NIFTY 50", 2000000);
    EngineSeries vix = contextSeries("INDIA VIX", 1500); // VIX ~15 - the gate passes
    SeriesProvider provider =
        key ->
            switch (key.tradingsymbol()) {
              case "SIGSYM" -> signal;
              case "NIFTY 50" -> nifty;
              case "INDIA VIX" -> vix;
              default -> null;
            };
    return IndicatorBank.build(
        definition, new StrategyDefinition.InstrumentRef("NSE", "SIGSYM"), provider);
  }

  @Test
  void contextCaseEvaluatesGateAndContribution() {
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    IndicatorBank bank = bank(definition);

    EntryEvaluator.Evaluation evaluation =
        EntryEvaluator.evaluate(definition, bank, 30).orElseThrow();
    ScoreBreakdown breakdown = evaluation.breakdown();

    // the VIX gate leaf carries the context-series level
    ScoreBreakdown.GateResult vixLeaf = breakdown.gate().children().get(0);
    assertThat(vixLeaf.rule()).isEqualTo("vix < 250");
    assertThat(vixLeaf.passed()).isTrue();
    assertThat(vixLeaf.operands().get("vix")).isNotNull();

    // the RS_VS_INDEX entry contributes through the override series
    ScoreBreakdown.IndicatorEntry rs =
        breakdown.indicators().stream()
            .filter(e -> e.alias().equals("rs_idx"))
            .findFirst()
            .orElseThrow();
    assertThat(rs.rawValue()).isNotNull();
    assertThat(rs.activationReason())
        .isIn(
            ScoreBreakdown.ActivationReason.ACTIVATED,
            ScoreBreakdown.ActivationReason.SCORE_BELOW_MIN,
            ScoreBreakdown.ActivationReason.MARGIN_NOT_MET);
    // gate-only vix never appears among scoring indicators
    assertThat(breakdown.indicators()).noneMatch(e -> e.alias().equals("vix"));
  }

  @Test
  void rendererInvariantHolds() {
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    IndicatorBank bank = bank(definition);

    ScoreBreakdown breakdown =
        EntryEvaluator.evaluate(definition, bank, 30).orElseThrow().breakdown();

    BigDecimal contributions =
        breakdown.indicators().stream()
            .filter(ScoreBreakdown.IndicatorEntry::activated)
            .map(ScoreBreakdown.IndicatorEntry::contribution)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal reconstructed =
        contributions.divide(breakdown.weightDenominator(), new MathContext(16));
    assertThat(breakdown.composite().subtract(reconstructed).abs())
        .as("composite = sum(contributions) / weightDenominator")
        .isLessThan(new BigDecimal("0.0000001"));
  }

  @Test
  void serializationIsByteStableAcrossIndependentBuilds() {
    StrategyDefinition d1 = StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    StrategyDefinition d2 = StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());

    String first =
        ScoreBreakdownJson.write(
            EntryEvaluator.evaluate(d1, bank(d1), 30).orElseThrow().breakdown());
    String second =
        ScoreBreakdownJson.write(
            EntryEvaluator.evaluate(d2, bank(d2), 30).orElseThrow().breakdown());

    assertThat(first).isEqualTo(second);
    assertThat(first).contains("\"activationReason\"").contains("\"weightDenominator\"");
    // C-2.6 decimals are exact-decimal STRINGS (never JSON numbers the browser would round)
    assertThat(first).contains("\"composite\":\"").contains("\"weightDenominator\":\"");
    assertThat(first).doesNotContainPattern("\"composite\":[0-9]");
  }

  @Test
  void warmupBarsAreUnscoreableNotZero() {
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    IndicatorBank bank = bank(definition);

    assertThat(EntryEvaluator.evaluate(definition, bank, 5))
        .as("RSI(14) still warming - no breakdown, never a zero score")
        .isEmpty();
  }

  @Test
  void breakdownParamsSurviveSerialization() {
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    String json =
        ScoreBreakdownJson.write(
            EntryEvaluator.evaluate(definition, bank(definition), 30).orElseThrow().breakdown());

    assertThat(json).contains("\"period\":14").contains("\"lookback\":5");
  }
}
