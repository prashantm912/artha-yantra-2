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
 * MV-9.2 defensive selling: the committed {@code minervini-vcp-defensive.yaml} exits on a daily close
 * back below the 20-day MA ({@code signal_exit crossunder(px, sma20)}). Runs a base → breakout → decline
 * that dips the close below the rising 20-day line while staying ABOVE the 8% stop, so the DEFENSIVE
 * exit (not the protective stop) is the active one. Uses only the existing {@code signal_exit} +
 * {@code crossunder} engine, so the frozen goldens stay byte-identical — this exercises the config.
 */
class SellingDisciplineTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 4, 1);
  private static final double PIVOT = 150.0;

  @Test
  void sellsOnACloseBackBelowThe20DayMaAboveTheStop() {
    List<GoldenSignalsJson.SignalEvent> events = run(craft());
    assertThat(entries(events)).as("vcp enters once on the breakout").isEqualTo(1);
    assertThat(exits(events))
        .as("exits once — on the close below the 20-day MA (above the 8% stop, so the defensive rule fired)")
        .isEqualTo(1);
  }

  // 32 daily bars: rise into the base, breakout to 152 on day 24 (entry, 8% stop = 139.84), then a
  // decline that closes below the ~148 20-day MA around day 28 while staying well above the stop — the
  // signal_exit crossunder(px, sma20) is the only exit that can fire (the stop's 139.84 is never hit).
  private static List<EngineCandle> craft() {
    double[] base = {147, 148, 146, 148, 147};
    double[] decline = {155, 152, 150, 147, 145, 144, 143}; // days 25-31, crosses below the 20d MA, > 139.84
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 31; d++) {
      double px;
      if (d <= 18) {
        px = 100.0 + (149.0 - 100.0) * d / 18.0;
      } else if (d <= 23) {
        px = base[d - 19];
      } else if (d == 24) {
        px = 152.0;
      } else {
        px = decline[d - 25];
      }
      bars.add(bar(DAY0.plusDays(d), px, d == 24 ? 3_000L : 1_000L));
    }
    return bars;
  }

  private static List<GoldenSignalsJson.SignalEvent> run(List<EngineCandle> daily) {
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivotContext());
    return new TickwiseGoldenRunner(defensiveDefinition(), "NSE", "NIFTY 50").run(daily, contexts);
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static long exits(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> "EXIT".equals(e.direction())).count();
  }

  private static StrategyDefinition defensiveDefinition() {
    try {
      Path yaml = goldenRoot().resolve("strategies/minervini-vcp-defensive.yaml");
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
