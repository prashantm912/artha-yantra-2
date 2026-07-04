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
 * MV-7.4: the {@code scaled_exit} partial-close (sell-into-strength). Runs the committed
 * {@code minervini-vcp-scaled.yaml} (sell ½ at +10%, ½ at +20%) over a breakout that runs from the
 * ~150 pivot up through +10% then +20%, and asserts the single entry produces TWO partial EXIT legs
 * each closing half the original position — the multi-leg close the single-precedence full-close path
 * could not express. Determinism is proven by a byte-equal second replay.
 */
class ScaledExitTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private static final double PIVOT = 150.0;

  @Test
  void breakoutSellsHalfAtPlus10ThenHalfAtPlus20() {
    List<GoldenSignalsJson.SignalEvent> events = run(craft());
    assertThat(entries(events)).as("one vcp entry on the breakout").isEqualTo(1);

    List<GoldenSignalsJson.SignalEvent> exits =
        events.stream().filter(e -> "EXIT".equals(e.direction())).toList();
    assertThat(exits).as("two scaled partial exits (+10% then +20%)").hasSize(2);
    assertThat(exits.get(0).qtyFraction()).as("first tier closes half").isEqualByComparingTo("0.5");
    assertThat(exits.get(1).qtyFraction()).as("second tier closes the other half").isEqualByComparingTo("0.5");
  }

  @Test
  void isDeterministic() {
    assertThat(GoldenSignalsJson.write("minervini-vcp-scaled.yaml", List.of("c"), run(craft())))
        .isEqualTo(GoldenSignalsJson.write("minervini-vcp-scaled.yaml", List.of("c"), run(craft())));
  }

  // 28 daily bars: the vcp base + breakout (days 0-24, pivot 150, entry at 152 on day 24), then a run
  // to 168 (+10.5%, fires tier 0 when day-25 completes) and 183 (+20.4%, fires tier 1 when day-26
  // completes); day 27 follows through to complete the day-26 bucket. 0.5+0.5 = the whole position.
  private static List<EngineCandle> craft() {
    double[] base = {147, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 27; d++) {
      double px;
      if (d <= 18) {
        px = 100.0 + (149.0 - 100.0) * d / 18.0; // rise into the base, tops < 150 pivot
      } else if (d <= 23) {
        px = base[d - 19]; // consolidation below the pivot
      } else if (d == 24) {
        px = 152.0; // breakout above the 150 pivot → entry (8% stop = 139.84)
      } else if (d == 25) {
        px = 168.0; // +10.5% → tier 0 (sell half)
      } else if (d == 26) {
        px = 183.0; // +20.4% → tier 1 (sell the rest)
      } else {
        px = 185.0; // follow-through completing the day-26 bucket
      }
      long vol = d == 24 ? 3_000L : 1_000L;
      bars.add(bar(DAY0.plusDays(d), px, vol));
    }
    return bars;
  }

  private static List<GoldenSignalsJson.SignalEvent> run(List<EngineCandle> daily) {
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivotContext());
    return new TickwiseGoldenRunner(scaledDefinition(), "NSE", "NIFTY 50").run(daily, contexts);
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static StrategyDefinition scaledDefinition() {
    try {
      Path yaml = goldenRoot().resolve("strategies/minervini-vcp-scaled.yaml");
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
