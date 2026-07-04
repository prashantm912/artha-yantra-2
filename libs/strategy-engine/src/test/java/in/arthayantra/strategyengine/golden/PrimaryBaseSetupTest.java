package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MV-6.6: the {@code primary_base} setup fires an entry as the close breaks out above the trailing
 * 52-week high (a fresh high) on expanding volume, and not without volume. Uses an inline variant of
 * {@code minervini-primary-base.yaml} with a testable {@code WEEK52_HIGH} period (20) over a
 * constructed base: a prior peak at 110, a consolidation below it, then a breakout to 112.
 * ({@code WEEK52_HIGH} at period 252 is the production value; the mechanism is identical.)
 */
class PrimaryBaseSetupTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);

  @Test
  void firesTheNewHighBreakoutOnVolume() {
    List<GoldenSignalsJson.SignalEvent> events = run(craftBase(3_000L));
    assertThat(entries(events)).as("primary-base enters on the new-high breakout").isEqualTo(1);
    assertThat(exits(events)).isZero();
  }

  @Test
  void doesNotFireWithoutBreakoutVolume() {
    List<GoldenSignalsJson.SignalEvent> events = run(craftBase(1_000L));
    assertThat(entries(events)).as("no volume → the breakout is blocked").isZero();
  }

  private static List<GoldenSignalsJson.SignalEvent> run(List<EngineCandle> primary) {
    StrategyDefinition definition = StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    return new TickwiseGoldenRunner(definition, "NSE", "NIFTY 50").run(primary, Map.of());
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static long exits(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> "EXIT".equals(e.direction())).count();
  }

  /**
   * 26 daily bars: rise 100→110 (prior peak, days 0–5), consolidate 104–108 BELOW 110 (days 6–21),
   * break out to 112 (day 22) clearing the trailing-20 high (110), follow-through (days 23–25). Only
   * day 22 makes a new 20-bar high. {@code breakoutVolume} = the day-22 volume (others 1000).
   */
  private static List<EngineCandle> craftBase(long breakoutVolume) {
    double[] base = {108, 106, 104, 107, 105, 106, 108, 107, 105, 106, 107, 108, 106, 105, 107, 108};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      double px;
      if (d <= 5) {
        px = 100.0 + (110.0 - 100.0) * d / 5.0; // rise to the prior peak 110
      } else if (d <= 21) {
        px = base[d - 6]; // consolidation below 110
      } else if (d == 22) {
        px = 112.0; // breakout above the trailing-20 high
      } else {
        px = 110.0 + d; // follow-through (triggers day-22 bucket completion)
      }
      long vol = d == 22 ? breakoutVolume : 1_000L;
      bars.add(bar(DAY0.plusDays(d), px, vol));
    }
    return bars;
  }

  private static EngineCandle bar(LocalDate d, double px, long volume) {
    BigDecimal p = BigDecimal.valueOf(px);
    return new EngineCandle(OffsetDateTime.of(d, LocalTime.of(9, 15), EngineSeries.IST), p, p, p, p, volume);
  }

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: minervini-primary-base-test
      name: "Primary Base Test"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: "NIFTY 50" }
      timeframes: { primary: 1d }
      indicators:
        - { name: SMA, alias: px, timeframe: 1d, params: { period: 1 }, weight: 1.0,
            normalize: { type: linear, from: 0, to: 100000 } }
        - { name: WEEK52_HIGH, alias: w52h, timeframe: 1d, params: { period: 20 }, weight: 1.0 }
        - { name: VOLUME_RATIO, alias: vol, timeframe: 1d, params: { lookback: 5 }, weight: 1.0 }
      entry_rules:
        direction: long
        gate:
          all:
            - crossover: { fast: px, slow: w52h }
            - "vol > 1.2"
        scoring: { threshold: 0.0 }
      exit_rules:
        - { type: stop_loss, params: { basis: atr_multiple, value: 3 } }
      risk:
        position_sizing: { method: percent_equity, params: { percent: 5 } }
        max_positions: 1
        session: { style: swing }
      """;
}
