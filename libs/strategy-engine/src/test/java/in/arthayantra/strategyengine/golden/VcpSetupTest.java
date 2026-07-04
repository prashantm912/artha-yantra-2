package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MV-6.1/6.3: the {@code vcp} breakout setup fires an entry as the daily close crosses above the
 * seeded VCP pivot ON expanding volume, and does NOT fire when the breakout lacks volume. Runs the
 * committed {@code minervini-vcp.yaml} over a constructed base: a rise into a ~150 pivot, a
 * consolidation below it, then a breakout — with {@code VCP_PIVOT} seeded as a context series at 150
 * (the Phase-5 buy trigger). Proves the context-seeded gate end-to-end in the deterministic runner
 * (the seeded-context = the same NEUTRAL-in-replay mechanism as the scalper confluence gate).
 */
class VcpSetupTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private static final double PIVOT = 150.0;

  @Test
  void firesTheBreakoutOnExpandingVolume() {
    List<GoldenSignalsJson.SignalEvent> events = run(craftBase(3_000L)); // 3x volume on the breakout
    assertThat(entries(events)).as("vcp enters once on the pivot breakout").isEqualTo(1);
    assertThat(exits(events)).as("swing hold — not squared off").isZero();
  }

  @Test
  void doesNotFireWhenTheBreakoutLacksVolume() {
    List<GoldenSignalsJson.SignalEvent> events = run(craftBase(1_000L)); // flat volume on the breakout
    assertThat(entries(events))
        .as("no entry: the crossover fires but the expanding-volume gate blocks it")
        .isZero();
  }

  private static List<GoldenSignalsJson.SignalEvent> run(List<EngineCandle> primary) {
    StrategyDefinition definition = vcpDefinition();
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivotContext());
    return new TickwiseGoldenRunner(definition, "NSE", "NIFTY 50").run(primary, contexts);
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static long exits(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> "EXIT".equals(e.direction())).count();
  }

  private static StrategyDefinition vcpDefinition() {
    try {
      Path yaml = goldenRoot().resolve("strategies/minervini-vcp.yaml");
      return StrategyCompiler.compile(
          StrategyDocuments.parse(Files.readString(yaml, StandardCharsets.UTF_8)).config());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 26 daily bars (one 1m bar each): a rise 100→149 into the base (days 0–18), a consolidation
   * 146–148 BELOW the 150 pivot (days 19–23), a breakout to 152 (day 24), and a follow-through
   * (day 25) whose arrival completes the day-24 bucket so the entry evaluates. Only day 24 crosses
   * above the pivot. {@code breakoutVolume} is the day-24 volume (all other days = 1000).
   */
  private static List<EngineCandle> craftBase(long breakoutVolume) {
    double[] base = {147, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      double px;
      if (d <= 18) {
        px = 100.0 + (149.0 - 100.0) * d / 18.0; // rise into the base, tops at 149 (< 150 pivot)
      } else if (d <= 23) {
        px = base[d - 19]; // consolidation below the pivot
      } else if (d == 24) {
        px = 152.0; // breakout above the 150 pivot
      } else {
        px = 153.0; // follow-through (triggers the day-24 bucket completion)
      }
      long vol = d == 24 ? breakoutVolume : 1_000L;
      bars.add(bar(DAY0.plusDays(d), LocalTime.of(9, 15), px, vol));
    }
    return bars;
  }

  /** The seeded VCP pivot: one context bar at/ before day 0 with close = the pivot level (150). */
  private static List<EngineCandle> pivotContext() {
    BigDecimal p = BigDecimal.valueOf(PIVOT);
    return List.of(
        new EngineCandle(OffsetDateTime.of(DAY0, LocalTime.MIDNIGHT, EngineSeries.IST), p, p, p, p, 0L));
  }

  private static EngineCandle bar(LocalDate d, LocalTime t, double px, long volume) {
    BigDecimal p = BigDecimal.valueOf(px);
    return new EngineCandle(OffsetDateTime.of(d, t, EngineSeries.IST), p, p, p, p, volume);
  }

  private static Path goldenRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("libs/strategy-engine/src/test/resources/golden");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("golden root not found");
  }
}
