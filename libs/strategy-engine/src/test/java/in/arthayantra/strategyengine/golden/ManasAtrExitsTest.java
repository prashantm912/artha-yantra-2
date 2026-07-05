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
 * Manas §3.5 exit doctrine on the NEW additive engine exit-types (ATR-cap stop + armed ATR trail +
 * exhaustion square-off). Runs the committed {@code manas-arora-atr-exits.yaml} end-to-end through the
 * tick-wise runner (the LIVE path) over a crafted daily series and proves (a) the entry fires on the
 * pivot breakout, (b) the position squares off on the too-fast/parabolic move, and (c) the entry-event
 * stop is the ATR-cap level (distance = min(2×ATR, 10%×entry)). Determinism is proven by a byte-equal
 * second replay. NOT in {@code GoldenDeterminismTest.FEATURES}, so the frozen goldens stay byte-identical.
 */
class ManasAtrExitsTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 2, 2);
  private static final double PIVOT = 150.0;

  @Test
  void entersOnTheBreakoutThenSquaresOffOnTheParabolicRun() {
    List<GoldenSignalsJson.SignalEvent> events = run(craftSeries());

    List<GoldenSignalsJson.SignalEvent> entries =
        events.stream().filter(e -> !"EXIT".equals(e.direction())).toList();
    List<GoldenSignalsJson.SignalEvent> exits =
        events.stream().filter(e -> "EXIT".equals(e.direction())).toList();

    assertThat(entries).as("one breakout entry over the pivot").hasSize(1);
    assertThat(exits).as("one square-off exit on the exhaustive run").hasSize(1);

    // The entry stop is the ATR-cap level: with the wide-ATR breakout bars 2×ATR exceeds 10%, so the
    // cap binds → stop = entry − 10%×entry. entryPrice is the day-30 breakout close (152.0).
    BigDecimal entryStop = entries.get(0).stopLoss();
    assertThat(entryStop)
        .as("the ATR-cap stop is reported as a protective level on the entry event")
        .isNotNull();
    assertThat(entryStop)
        .as("cap binds → stop distance = 10% of the ~152 entry")
        .isEqualByComparingTo(new BigDecimal("136.80"));
  }

  @Test
  void isDeterministic() {
    assertThat(
            GoldenSignalsJson.write("manas-arora-atr-exits.yaml", List.of("c"), run(craftSeries())))
        .isEqualTo(
            GoldenSignalsJson.write("manas-arora-atr-exits.yaml", List.of("c"), run(craftSeries())));
  }

  // 34 daily bars: 30 warmup bars flat at 148 with a WIDE ±10.0 intrabar range (so ATR(20) is large →
  // 2×ATR > 10% and the cap binds), each below the 150 pivot; a breakout to 152 on day 30 (crosses the
  // pivot on 3× volume, above the 20-day MA → entry, ATR-cap stop = 152 − 15.2 = 136.80); then a
  // parabolic run to 235 on day 32 (+58% over the ~148 SMA(10) ≫ +40% → square_off). Day 33 follows
  // through to complete the day-32 bucket so the exit evaluates.
  private static List<EngineCandle> craftSeries() {
    double[] run = {152, 210, 235}; // days 30..32: breakout then the exhaustive parabolic push
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 33; d++) {
      double px;
      double halfRange;
      if (d <= 29) {
        px = 148.0; // warmup below the pivot; wide range → large ATR so the cap binds
        halfRange = 10.0;
      } else if (d <= 32) {
        px = run[d - 30];
        halfRange = 2.0;
      } else {
        px = 236.0; // follow-through completing the day-32 bucket
        halfRange = 2.0;
      }
      long vol = d == 30 ? 3_000L : 1_000L; // expanding volume only on the breakout bar
      bars.add(bar(DAY0.plusDays(d), px, halfRange, vol));
    }
    return bars;
  }

  private static List<GoldenSignalsJson.SignalEvent> run(List<EngineCandle> dailyBars) {
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivotContext());
    return new TickwiseGoldenRunner(definition(), "NSE", "NIFTY 50").run(dailyBars, contexts);
  }

  private static StrategyDefinition definition() {
    try {
      Path yaml = goldenRoot().resolve("strategies/manas-arora-atr-exits.yaml");
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

  private static EngineCandle bar(LocalDate d, double px, double halfRange, long volume) {
    BigDecimal close = BigDecimal.valueOf(px);
    return new EngineCandle(
        OffsetDateTime.of(d, LocalTime.of(9, 15), EngineSeries.IST),
        close,
        BigDecimal.valueOf(px + halfRange),
        BigDecimal.valueOf(px - halfRange),
        close,
        volume);
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
