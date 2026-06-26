package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 2b-E3: the tick-wise runner must roll up a {@code 3m} primary (the Siva scalper execution clock) the
 * same way it already does 5m/15m/1h — before this it threw {@code "rolls up 5m/15m/1h primaries; got
 * 3m"}, which made EVERY 3m scalper backtest fail at submission. This pins the previously-throwing
 * coarse-primary path for 3m: a 3m-primary strategy fed 1m bars runs to completion and is deterministic.
 */
class TickwiseThreeMinuteRollupTest {

  // minimal schema-valid 3m-primary strategy — the universe mode is irrelevant to the runner (it is
  // constructed against an explicit signal instrument); a permissive gate keeps the path exercised.
  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: tickwise-3m-rollup-probe
      name: "Tickwise 3m rollup probe"
      version: 1.0.0
      universe:
        mode: index_constituents
        index: "NIFTY 50"
      timeframes:
        primary: 3m
      indicators:
        - { name: EMA, alias: ema_fast, timeframe: 3m, params: { period: 5 } }
      entry_rules:
        direction: long
        gate: { all: ["close > 0"] }
        scoring: { threshold: 0.2 }
      exit_rules:
        - { type: time_stop, params: { max_bars: 3 } }
      risk:
        position_sizing: { method: premium_budget, params: { budget_inr: 15000 } }
        max_positions: 1
        session: { style: intraday, window: { from: "09:15", to: "15:00" }, square_off: "15:15" }
      """;

  /** One trading session of 1m bars from 09:15 IST, a gentle uptrend (30 bars = 10 full 3m buckets). */
  private static List<EngineCandle> oneMinuteBars() {
    List<EngineCandle> bars = new ArrayList<>();
    OffsetDateTime t = OffsetDateTime.parse("2026-06-15T09:15:00+05:30");
    for (int i = 0; i < 30; i++) {
      BigDecimal c = new BigDecimal(25000 + i);
      bars.add(new EngineCandle(t.plusMinutes(i), c, c.add(BigDecimal.ONE), c, c, 1000L));
    }
    return bars;
  }

  @Test
  void rollsUpA3mPrimaryWithoutThrowingAndDeterministically() {
    StrategyDefinition def = StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    assertThat(def.primaryTimeframe()).isEqualTo("3m");

    TickwiseGoldenRunner runner = new TickwiseGoldenRunner(def, "NSE", "NIFTY 50");
    List<EngineCandle> bars = oneMinuteBars();

    assertThatCode(() -> runner.run(bars, Map.of()))
        .as("a 3m primary must roll up like 5m/15m/1h, not throw")
        .doesNotThrowAnyException();

    String first = GoldenSignalsJson.write("probe.yaml", List.of("probe.csv"), runner.run(bars, Map.of()));
    String second = GoldenSignalsJson.write("probe.yaml", List.of("probe.csv"), runner.run(bars, Map.of()));
    assertThat(second).isEqualTo(first);
  }
}
