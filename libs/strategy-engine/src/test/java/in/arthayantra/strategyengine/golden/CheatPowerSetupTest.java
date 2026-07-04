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
 * MV-6.4/6.5: the {@code cheat_3c} and {@code power_play} setups. Both are seeded-context breakout
 * entries over the same constructed base (rise → consolidation below the 150 level → breakout to
 * 152): cheat_3c breaks out above the seeded cheat-pause high; power_play breaks out above the seeded
 * VCP pivot BUT only when the seeded THRUST flag is on. Proves the context-gated entries + that the
 * power-play thrust gate is load-bearing.
 */
class CheatPowerSetupTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private static final double LEVEL = 150.0;

  @Test
  void cheatFiresTheBreakoutAboveThePause() {
    List<GoldenSignalsJson.SignalEvent> events =
        run("minervini-cheat-3c.yaml", Map.of(key("MINERVINI_CHEAT"), levelSeries(LEVEL)));
    assertThat(entries(events)).as("cheat enters on the pause-high breakout").isEqualTo(1);
  }

  @Test
  void powerPlayFiresWhenTheThrustFlagIsOn() {
    List<GoldenSignalsJson.SignalEvent> events =
        run(
            "minervini-power-play.yaml",
            Map.of(
                key("MINERVINI_PIVOT"), levelSeries(LEVEL),
                key("MINERVINI_THRUST"), levelSeries(1.0)));
    assertThat(entries(events)).as("power-play enters with a prior thrust").isEqualTo(1);
  }

  @Test
  void powerPlayBlockedWhenNoThrust() {
    List<GoldenSignalsJson.SignalEvent> events =
        run(
            "minervini-power-play.yaml",
            Map.of(
                key("MINERVINI_PIVOT"), levelSeries(LEVEL),
                key("MINERVINI_THRUST"), levelSeries(0.0)));
    assertThat(entries(events)).as("no thrust → the pivot breakout is not a power play").isZero();
  }

  private static List<GoldenSignalsJson.SignalEvent> run(
      String yamlFile, Map<SeriesKey, List<EngineCandle>> contexts) {
    StrategyDefinition definition = load(yamlFile);
    return new TickwiseGoldenRunner(definition, "NSE", "NIFTY 50").run(craftBase(), contexts);
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static SeriesKey key(String symbol) {
    return new SeriesKey("NSE", symbol, "1d");
  }

  /** A single context bar at/before day 0 carrying {@code value} as the seeded level/flag. */
  private static List<EngineCandle> levelSeries(double value) {
    BigDecimal v = BigDecimal.valueOf(value);
    return List.of(
        new EngineCandle(
            OffsetDateTime.of(DAY0, LocalTime.MIDNIGHT, EngineSeries.IST), v, v, v, v, 0L));
  }

  /** Rise 100→149 (days 0–18), consolidate 146–148 below the 150 level (19–23), breakout 152 (24). */
  private static List<EngineCandle> craftBase() {
    double[] base = {147, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      double px;
      if (d <= 18) {
        px = 100.0 + (149.0 - 100.0) * d / 18.0;
      } else if (d <= 23) {
        px = base[d - 19];
      } else if (d == 24) {
        px = 152.0;
      } else {
        px = 153.0;
      }
      BigDecimal p = BigDecimal.valueOf(px);
      long vol = d == 24 ? 3_000L : 1_000L;
      bars.add(
          new EngineCandle(
              OffsetDateTime.of(DAY0.plusDays(d), LocalTime.of(9, 15), EngineSeries.IST), p, p, p, p, vol));
    }
    return bars;
  }

  private static StrategyDefinition load(String yamlFile) {
    try {
      Path yaml = goldenRoot().resolve("strategies/" + yamlFile);
      return StrategyCompiler.compile(
          StrategyDocuments.parse(Files.readString(yaml, StandardCharsets.UTF_8)).config());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
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
