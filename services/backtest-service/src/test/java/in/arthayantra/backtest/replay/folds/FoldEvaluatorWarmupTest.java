package in.arthayantra.backtest.replay.folds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.backtest.replay.MetricsCalculator;
import in.arthayantra.backtest.replay.ReplayEngine;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay;
import in.arthayantra.backtest.replay.options.PremiumProvenance;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AY-SL-03: {@link FoldEvaluator} must warm each window's indicators from the run's data start so a
 * long-lookback gate can evaluate inside a fold too short to self-warm — while the OOS fold's trades
 * stay strictly in-window. Before the fix, the OOS window replayed COLD from its own first bar, so a
 * {@code WEEK52_HIGH(30)} gate was structurally unable to fire in a 20-bar OOS fold and the fold's
 * objective was always empty/invalid.
 *
 * <p>Fixture: Day 1 = 40 flat warmup bars; Day 2 (the OOS window) makes a fresh 30-bar high at bar 5.
 * The OOS window on its own (20 bars) can never warm {@code WEEK52_HIGH(30)} — proven by the cold
 * control — yet the fold, warmed by Day 1, fires it and closes a trade.
 */
class FoldEvaluatorWarmupTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal EQUITY = new BigDecimal("1000000");

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: warmup-fold-longlookback
      name: "Warmup Fold Long Lookback"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: "TEST" }
      timeframes: { primary: 1m }
      indicators:
        - { name: SMA, alias: px, timeframe: 1m, params: { period: 1 }, weight: 1.0,
            normalize: { type: linear, from: 0, to: 100000 } }
        - { name: WEEK52_HIGH, alias: hh, timeframe: 1m, params: { period: 30 }, weight: 1.0 }
      entry_rules:
        direction: long
        gate:
          all:
            - crossover: { fast: px, slow: hh }
        scoring: { threshold: 0.0 }
      exit_rules:
        - { type: stop_loss, params: { basis: percent, value: 8 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonNode config = StrategyDocuments.parse(YAML).config();
  private final StrategyDefinition definition = StrategyCompiler.compile(config);

  private final FoldEvaluator evaluator =
      new FoldEvaluator(
          new ReplayEngine(mapper),
          mock(OptionsPremiumReplay.class),
          new PremiumProvenance(),
          new MetricsCalculator(mapper));

  private static final OffsetDateTime TEST_FROM = OffsetDateTime.of(2026, 1, 6, 0, 0, 0, 0, IST);

  @Test
  void oosFoldWarmsLongLookbackGateAndKeepsTradesInWindow() {
    List<EngineCandle> primary1m = buildTwoDays();
    Fold fold =
        new Fold(
            0,
            OffsetDateTime.of(2026, 1, 5, 0, 0, 0, 0, IST), // trainFrom (data start)
            TEST_FROM, // trainTo == testFrom
            TEST_FROM,
            OffsetDateTime.of(2026, 1, 7, 0, 0, 0, 0, IST)); // testTo

    List<FoldResult> results =
        evaluator.evaluate(
            config,
            List.of(fold),
            definition,
            "NSE",
            "TEST",
            primary1m,
            List.<EngineCandle>of(),
            Map.<SeriesKey, List<EngineCandle>>of(),
            EQUITY,
            CostConfig.defaults(),
            true);

    assertThat(results).hasSize(1);
    FoldResult fr = results.get(0);

    // The warmed OOS fold fires the long-lookback gate and closes a trade.
    assertThat(fr.oosTradeCount()).as("OOS fold warms the 30-bar gate and trades").isGreaterThanOrEqualTo(1);
    assertThat(fr.oosTrades()).isNotEmpty();
    // Every OOS trade is strictly in-window — no warmup activity leaks into the fold.
    for (Trade t : fr.oosTrades()) {
      assertThat(t.entryTs()).as("OOS trade entry is in-window").isAfterOrEqualTo(TEST_FROM);
    }

    // Control: the OOS window replayed COLD (its own 20 bars, no warmup prefix) can NEVER warm
    // WEEK52_HIGH(30) — the exact defect the fix removes.
    List<EngineCandle> oosOnly = FoldEvaluator.slice(primary1m, fold.testFrom(), fold.testTo());
    ReplayResult coldOos =
        new ReplayEngine(mapper)
            .replay(
                definition, "NSE", "TEST", oosOnly, Map.<SeriesKey, List<EngineCandle>>of(),
                EQUITY, CostConfig.defaults(), true);
    assertThat(coldOos.trades()).as("cold OOS fold cannot warm the gate").isEmpty();
  }

  /**
   * Day 1 (2026-01-05): 40 flat @100 warmup bars. Day 2 (2026-01-06, the OOS window): 20 bars — flat
   * @100 for the first five, a fresh-high breakout to 105 at bar 5, then flat @105. The breakout at
   * OOS-local bar 5 needs {@code WEEK52_HIGH(30)} warm, which only Day 1's depth provides.
   */
  private static List<EngineCandle> buildTwoDays() {
    List<EngineCandle> bars = new ArrayList<>(60);
    OffsetDateTime day1 = OffsetDateTime.of(2026, 1, 5, 9, 15, 0, 0, IST);
    for (int i = 0; i < 40; i++) {
      bars.add(flat(day1.plusMinutes(i), 100));
    }
    OffsetDateTime day2 = OffsetDateTime.of(2026, 1, 6, 9, 15, 0, 0, IST);
    for (int i = 0; i < 20; i++) {
      OffsetDateTime ts = day2.plusMinutes(i);
      if (i < 5) {
        bars.add(flat(ts, 100));
      } else if (i == 5) {
        bars.add(candle(ts, 100, 105, 100, 105)); // fresh 30-bar high (needs Day-1 warmup)
      } else {
        bars.add(flat(ts, 105));
      }
    }
    return bars;
  }

  private static EngineCandle flat(OffsetDateTime ts, long px) {
    BigDecimal p = BigDecimal.valueOf(px);
    return new EngineCandle(ts, p, p, p, p, 100L);
  }

  private static EngineCandle candle(OffsetDateTime ts, long o, long h, long l, long c) {
    return new EngineCandle(
        ts, BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l),
        BigDecimal.valueOf(c), 100L);
  }
}
