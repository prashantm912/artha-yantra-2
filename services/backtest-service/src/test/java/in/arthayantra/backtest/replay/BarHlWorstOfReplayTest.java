package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.fills.TouchBasis;
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
 * P1-10 (B3) demonstration: the opt-in {@code session.touch_basis: bar_hl_worstof} exit basis
 * (driven into {@link ReplayEngine} as {@code oneMinuteCovered=false}) resolves a protective stop
 * CONSERVATIVELY on the bar's HIGH/LOW worst-of via {@code IntrabarExitResolver}, catching an
 * intrabar spike a close-basis exit misses. The SAME strategy + candles under the default (covered)
 * path holds the position through the wick to end-of-data at a flattering price; under bar_hl_worstof
 * the stop fills AT the stop level (or worse, never better) on the wick bar — earlier and worse. Runs
 * {@code minervini-vcp-scaled} (8% percent stop) directly at the ReplayEngine level, so it exercises
 * the exit resolution without any schema/knob plumbing (the knob→oneMinuteCovered wiring is covered by
 * {@code TouchBasisClassifierTest} + {@code BacktestRunner}).
 */
class BarHlWorstOfReplayTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @Test
  void barHlWorstOfFillsTheStopOnTheWickWhileCloseBasisHoldsThrough() {
    StrategyDefinition definition = scaled();
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivot(150.0));
    List<EngineCandle> primary = craftWick();

    ReplayResult covered =
        engine.replay(
            definition, "NSE", "NIFTY 50", primary, contexts,
            new BigDecimal("1000000"), CostConfig.defaults(), true);
    ReplayResult worstOf =
        engine.replay(
            definition, "NSE", "NIFTY 50", primary, contexts,
            new BigDecimal("1000000"), CostConfig.defaults(), false);

    assertThat(covered.trades()).as("close-basis holds through the wick to end-of-data").hasSize(1);
    assertThat(worstOf.trades()).as("bar_hl_worstof books the stop on the wick").hasSize(1);

    Trade coveredTrade = covered.trades().get(0);
    Trade worstOfTrade = worstOf.trades().get(0);

    // same entry — signals are timing/basis-agnostic, only the exit resolution differs
    assertThat(worstOfTrade.entryPrice()).isEqualByComparingTo(coveredTrade.entryPrice());

    // provenance: the default path stamps INTRABAR_1M (coarse exit_intrabar), the opt-in BAR_HL_WORSTOF
    assertThat(coveredTrade.touchBasis()).isEqualTo(TouchBasis.INTRABAR_1M);
    assertThat(worstOfTrade.touchBasis()).isEqualTo(TouchBasis.BAR_HL_WORSTOF);

    // the entry-time protective stop = 8% below the 152 breakout close = 139.84 (same on both paths)
    BigDecimal stop = worstOfTrade.stopLoss();
    assertThat(stop).isEqualByComparingTo("139.84");
    assertThat(coveredTrade.stopLoss()).isEqualByComparingTo(stop);

    // the CONSERVATIVE fill: at the stop level or worse (never better), and a stop_loss reason
    assertThat(worstOfTrade.exitPrice())
        .as("the stop fills at the stop level or worse (never better)")
        .isLessThanOrEqualTo(stop);
    assertThat(worstOfTrade.exitReason()).isEqualTo("stop_loss");

    // earlier + worse than the flattered close-basis hold-through
    assertThat(worstOfTrade.exitTs())
        .as("the touch exit is earlier than the held-to-end close exit")
        .isBefore(coveredTrade.exitTs());
    assertThat(worstOfTrade.exitPrice())
        .as("close-basis exits far above the stop; the touch exit books at the stop")
        .isLessThan(coveredTrade.exitPrice());
    assertThat(worstOfTrade.pnl())
        .as("a booked stop is a worse P&L than holding through the wick")
        .isLessThan(coveredTrade.pnl());
  }

  // days 0-18 ramp (warmup, below the 150 pivot), 19-23 base, 24 breakout close 152 (crossover px>pivot
  // + a volume spike) -> entry signal that fills next_open at 152 -> 8% stop = 139.84; 25-27 flat 152
  // (position held), 28 WICK low 138 closes back at 150 (close-basis misses; H/L worst-of books the
  // stop), 29 flat 150. The wick sits well past the entry fill bar so the overlay is eligible.
  private static List<EngineCandle> craftWick() {
    double[] base = {147, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 23; d++) {
      double px = d <= 18 ? 100.0 + (149.0 - 100.0) * d / 18.0 : base[d - 19];
      flat(bars, d, px, 1_000L);
    }
    flat(bars, 24, 152.0, 3_000L); // breakout: crossover(px>pivot) + volume spike -> entry signal
    flat(bars, 25, 152.0, 1_000L);
    flat(bars, 26, 152.0, 1_000L);
    flat(bars, 27, 152.0, 1_000L);
    ohlc(bars, 28, 150.0, 151.0, 138.0, 150.0, 1_000L); // wick: low 138 < 139.84 stop, close 150 > stop
    flat(bars, 29, 150.0, 1_000L);
    return bars;
  }

  private static void flat(List<EngineCandle> bars, int d, double px, long vol) {
    ohlc(bars, d, px, px, px, px, vol);
  }

  private static void ohlc(
      List<EngineCandle> bars, int d, double o, double h, double l, double c, long vol) {
    bars.add(
        new EngineCandle(
            OffsetDateTime.of(DAY0.plusDays(d), LocalTime.of(9, 15), EngineSeries.IST),
            BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l),
            BigDecimal.valueOf(c), vol));
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
