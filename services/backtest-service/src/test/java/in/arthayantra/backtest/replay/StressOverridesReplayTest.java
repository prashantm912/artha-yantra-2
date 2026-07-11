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
 * EVO §3.2.5 cost-stress at the candle {@link ReplayEngine} level: the request-level
 * {@code slippageMultiplier} rides {@link CostConfig#withSlippageMultiplier} into every fill, so a
 * stressed run's BUY entry fills higher and its round-trip ends poorer than the unstressed run — while
 * a multiplier of {@code 1} reproduces {@link CostConfig#defaults()} byte-for-byte (the parity
 * guarantee an absent {@code stressOverrides} field relies on). Same fixture as {@code
 * ScaledBacktestTest} (a breakout run-up through both scale-out tiers) so the run produces trades.
 */
class StressOverridesReplayTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @Test
  void multiplierOfOneReproducesTheUnstressedRunByteForByte() {
    ReplayResult unstressed = run(CostConfig.defaults());
    ReplayResult stressedByOne =
        run(CostConfig.defaults().withSlippageMultiplier(BigDecimal.ONE));

    assertThat(stressedByOne.trades())
        .as("multiplier 1 == unstressed defaults() (absent stressOverrides parity)")
        .isEqualTo(unstressed.trades());
    assertThat(stressedByOne.finalEquity()).isEqualByComparingTo(unstressed.finalEquity());
  }

  @Test
  void doubledSlippageFillsWorseAndEndsPoorer() {
    ReplayResult unstressed = run(CostConfig.defaults());
    ReplayResult stressed = run(CostConfig.defaults().withSlippageMultiplier(new BigDecimal("2")));

    assertThat(unstressed.trades()).isNotEmpty();
    // The shared entry BUY pays MORE slippage under stress (fillPrice = reference + slippage).
    assertThat(stressed.trades().get(0).entryPrice())
        .as("a stressed BUY entry fills higher")
        .isGreaterThan(unstressed.trades().get(0).entryPrice());
    // The whole stressed round-trip ends with less equity than the unstressed one.
    assertThat(stressed.finalEquity())
        .as("wider slippage → poorer final equity")
        .isLessThan(unstressed.finalEquity());
  }

  private ReplayResult run(CostConfig costs) {
    StrategyDefinition definition = scaled();
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivot(150.0));
    return engine.replay(
        definition, "NSE", "NIFTY 50", craftRun(), contexts, new BigDecimal("1000000"), costs, true);
  }

  // Base → breakout at 152 (entry) → +10.5% (168) → +20.4% (183) → rising follow-through (186/188/190).
  private static List<EngineCandle> craftRun() {
    double[] base = {147, 148, 146, 148, 147};
    double[] runUp = {186, 188, 190};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 29; d++) {
      double px;
      if (d <= 18) {
        px = 100.0 + (149.0 - 100.0) * d / 18.0;
      } else if (d <= 23) {
        px = base[d - 19];
      } else if (d == 24) {
        px = 152.0;
      } else if (d == 25) {
        px = 168.0;
      } else if (d == 26) {
        px = 183.0;
      } else {
        px = runUp[d - 27];
      }
      add(bars, d, px, d == 24 ? 3_000L : 1_000L);
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

  private static StrategyDefinition scaled() {
    try {
      Path yaml = goldenRoot().resolve("strategies/minervini-vcp-scaled.yaml");
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
