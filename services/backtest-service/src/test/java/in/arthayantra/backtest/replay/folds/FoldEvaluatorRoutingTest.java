package in.arthayantra.backtest.replay.folds;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.replay.CostConfig;
import in.arthayantra.backtest.replay.EquityPoint;
import in.arthayantra.backtest.replay.MetricsCalculator;
import in.arthayantra.backtest.replay.ReplayEngine;
import in.arthayantra.backtest.replay.ReplayResult;
import in.arthayantra.backtest.replay.Trade;
import in.arthayantra.backtest.replay.options.OptionsPremiumReplay;
import in.arthayantra.backtest.replay.options.PremiumProvenance;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase-31 fold replay MUST route an options_of_underlying strategy through the Part-2
 * premium-as-primary engine ({@link OptionsPremiumReplay}), exactly like {@code BacktestRunner}'s
 * full-window path — NOT the bare candle-close {@link ReplayEngine}. The regression this guards:
 * before the fix, {@link FoldEvaluator} always used the bare engine, so every OOS fold replayed the
 * underlying candles instead of the option premium, produced ~0 option trades, and was excluded under
 * {@code min_trades} → {@code oos_fold_mean} stayed NULL → every fold-based optimizer sweep failed.
 */
class FoldEvaluatorRoutingTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final BigDecimal EQUITY = new BigDecimal("1000000");

  private final ReplayEngine plain = mock(ReplayEngine.class);
  private final OptionsPremiumReplay premium = mock(OptionsPremiumReplay.class);
  private final MetricsCalculator metrics = mock(MetricsCalculator.class);
  private final FoldEvaluator evaluator =
      new FoldEvaluator(plain, premium, new PremiumProvenance(), metrics);

  private static ObjectNode config(String mode) {
    ObjectNode c = M.createObjectNode();
    c.putObject("universe").put("mode", mode);
    return c;
  }

  private static ReplayResult empty() {
    return new ReplayResult(
        List.<GoldenSignalsJson.SignalEvent>of(),
        List.<Trade>of(),
        List.<EquityPoint>of(),
        List.<EquityPoint>of(),
        EQUITY,
        EQUITY,
        0L,
        0L);
  }

  private static List<Fold> oneFold() {
    OffsetDateTime b = OffsetDateTime.parse("2026-01-05T00:00:00+05:30");
    return List.of(new Fold(0, b, b.plusDays(2), b.plusDays(2), b.plusDays(4)));
  }

  private StrategyDefinition def() {
    StrategyDefinition d = mock(StrategyDefinition.class);
    when(d.primaryTimeframe()).thenReturn("1m");
    return d;
  }

  @Test
  void optionsStrategyFoldRoutesThroughPremiumReplay() {
    // 10-arg core: the 8 replay inputs + a per-fold GateCoverage + the cost-stress slippageMultiplier.
    when(premium.replay(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(empty());
    when(metrics.compute(any(), any(), any(), any(), any(), anyLong(), anyLong()))
        .thenReturn(FoldTestFixtures.metricsWithSharpe(1.0));

    evaluator.evaluate(
        config("options_of_underlying"),
        oneFold(),
        def(),
        "NSE",
        "NIFTY 50",
        List.<EngineCandle>of(),
        List.<EngineCandle>of(),
        Map.of(),
        EQUITY,
        CostConfig.defaults(),
        true);

    // train + OOS = two premium replays per fold; the bare candle-close engine is never touched.
    verify(premium, times(2))
        .replay(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verifyNoInteractions(plain);
  }

  @Test
  void nonOptionsStrategyFoldUsesPlainEngine() {
    when(plain.replay(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(empty());
    when(metrics.compute(any(), any(), any(), any(), any(), anyLong(), anyLong()))
        .thenReturn(FoldTestFixtures.metricsWithSharpe(1.0));

    evaluator.evaluate(
        config("index_constituents"),
        oneFold(),
        def(),
        "NSE",
        "RELIANCE",
        List.<EngineCandle>of(),
        List.<EngineCandle>of(),
        Map.of(),
        EQUITY,
        CostConfig.defaults(),
        true);

    verify(plain, times(2)).replay(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    verifyNoInteractions(premium);
  }
}
