package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * MV-8.2/8.3: a swing (daily-primary) Minervini setup backtests through the SAME ReplayEngine as the
 * scalpers — since signal generation runs through the shared {@code TickwiseGoldenRunner}, the swing
 * daily-primary + no-square-off handling (PR-E) carries into replay for free. Runs the committed
 * {@code minervini-vcp.yaml} over a constructed base → breakout → decline (VCP_PIVOT seeded at 150):
 * the position enters on the breakout, HOLDS across multiple daily sessions, and exits on the stop —
 * a genuine multi-day swing trade, reproduced identically on two replays (determinism).
 */
class SwingBacktestTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @Test
  void swingSetupProducesAMultiDayTradeDeterministically() {
    StrategyDefinition definition = vcp();
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivot(150.0));
    List<EngineCandle> primary = craftBaseBreakoutDecline();

    ReplayResult first =
        engine.replay(
            definition, "NSE", "NIFTY 50", primary, contexts,
            new BigDecimal("1000000"), CostConfig.defaults(), true);
    ReplayResult second =
        engine.replay(
            definition, "NSE", "NIFTY 50", primary, contexts,
            new BigDecimal("1000000"), CostConfig.defaults(), true);

    assertThat(first.trades()).as("the swing setup produces a trade").isNotEmpty();
    Trade trade = first.trades().get(0);
    assertThat(trade.entryTs().toLocalDate())
        .as("entry precedes exit — the position was held, not squared off intraday")
        .isBefore(trade.exitTs().toLocalDate());
    assertThat(trade.barsHeld()).as("held across ≥2 daily sessions").isGreaterThanOrEqualTo(2);
    assertThat(second.trades())
        .as("two replays produce the identical trade list (determinism)")
        .isEqualTo(first.trades());
  }

  /** Rise 100→149 into the base, consolidate below the 150 pivot, break out to 152, then decline. */
  private static List<EngineCandle> craftBaseBreakoutDecline() {
    double[] base = {147, 148, 146, 148, 147};
    double[] decline = {153, 151, 149, 147, 144, 141, 138, 135, 132};
    List<EngineCandle> bars = new ArrayList<>();
    int d = 0;
    for (; d <= 18; d++) {
      add(bars, d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L);
    }
    for (; d <= 23; d++) {
      add(bars, d, base[d - 19], 1_000L);
    }
    add(bars, 24, 152.0, 3_000L); // breakout
    for (int i = 0; i < decline.length; i++) {
      add(bars, 25 + i, decline[i], 1_000L); // decline → hits the stop days later
    }
    return bars;
  }

  private static void add(List<EngineCandle> bars, int d, double px, long vol) {
    BigDecimal p = BigDecimal.valueOf(px);
    bars.add(
        new EngineCandle(
            OffsetDateTime.of(DAY0.plusDays(d), LocalTime.of(9, 15), EngineSeries.IST), p, p, p, p, vol));
  }

  private static List<EngineCandle> pivot(double level) {
    BigDecimal v = BigDecimal.valueOf(level);
    return List.of(
        new EngineCandle(OffsetDateTime.of(DAY0, LocalTime.MIDNIGHT, EngineSeries.IST), v, v, v, v, 0L));
  }

  private static StrategyDefinition vcp() {
    try {
      Path yaml =
          goldenRoot().resolve("strategies/minervini-vcp.yaml");
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
