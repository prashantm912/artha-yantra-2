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
 * MV-7.4: the Minervini 50-day-MA swing trail. Runs the committed {@code minervini-vcp-trail.yaml}
 * (vcp entry + the ~8% initial stop + a {@code trailing_stop basis=indicator alias=sma50}) and proves
 * the position exits when a daily close breaks BELOW the 50-day MA while still ABOVE the 8% stop — so
 * the trail, not the protective stop, is the active exit (the "sell on a close below the 50-day line"
 * doctrine). The 50-day-MA trail is an existing engine capability (trailing_stop / indicator basis),
 * so no engine changed and the frozen goldens stay byte-identical — this only exercises the config.
 */
class SwingTrailTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 3, 2);
  private static final double PIVOT = 150.0;

  @Test
  void entersOnTheBreakoutThenTrailsOutOnACloseBelowThe50DayMa() {
    List<GoldenSignalsJson.SignalEvent> events = run(craftTrailSeries());
    assertThat(entries(events)).as("vcp-trail enters once on the pivot breakout").isEqualTo(1);
    assertThat(exits(events))
        .as("exits once — on the close below the 50-day MA (above the 8% stop, so the trail fired)")
        .isEqualTo(1);
  }

  // 58 daily bars: a 50-bar warmup flat at 148 (below the 150 pivot, so the 50-day MA warms just under
  // the pivot), a breakout to 152 on day 50 (crosses the pivot on 3x volume → entry, 8% stop = 139.84),
  // a 5-day run to 159, then a close at 148 on day 56 that is BELOW the day-56 50-day MA (~148.88) yet
  // well ABOVE the 139.84 stop — the trail fires. Day 57 completes the day-56 bucket so the exit evaluates.
  private static List<EngineCandle> craftTrailSeries() {
    double[] run = {152, 153, 154, 156, 158, 159}; // days 50..55: breakout + run
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 57; d++) {
      double px;
      if (d <= 49) {
        px = 148.0; // warmup below the pivot; the 50-day MA settles at ~148
      } else if (d <= 55) {
        px = run[d - 50];
      } else if (d == 56) {
        px = 148.0; // close below the ~148.88 50-day MA, above the 139.84 stop → the trail exits
      } else {
        px = 149.0; // follow-through that completes the day-56 bucket
      }
      long vol = d == 50 ? 3_000L : 1_000L; // expanding volume only on the breakout bar
      bars.add(bar(DAY0.plusDays(d), px, vol));
    }
    return bars;
  }

  private static List<GoldenSignalsJson.SignalEvent> run(List<EngineCandle> dailyBars) {
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivotContext());
    return new TickwiseGoldenRunner(trailDefinition(), "NSE", "NIFTY 50").run(dailyBars, contexts);
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static long exits(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> "EXIT".equals(e.direction())).count();
  }

  private static StrategyDefinition trailDefinition() {
    try {
      Path yaml = goldenRoot().resolve("strategies/minervini-vcp-trail.yaml");
      return StrategyCompiler.compile(
          StrategyDocuments.parse(Files.readString(yaml, StandardCharsets.UTF_8)).config());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<EngineCandle> pivotContext() {
    BigDecimal p = BigDecimal.valueOf(PIVOT);
    return List.of(
        new EngineCandle(OffsetDateTime.of(DAY0, LocalTime.MIDNIGHT, EngineSeries.IST), p, p, p, p, 0L));
  }

  private static EngineCandle bar(LocalDate d, double px, long volume) {
    BigDecimal p = BigDecimal.valueOf(px);
    return new EngineCandle(OffsetDateTime.of(d, LocalTime.of(9, 15), EngineSeries.IST), p, p, p, p, volume);
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
