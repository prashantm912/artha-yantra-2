package in.arthayantra.strategysignal.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Phase-9 swing engine's core: {@link MinerviniSwingEngine#buildBank} seeds the per-symbol VCP
 * pivot as a flat context series, and the FROZEN {@link EntryEvaluator}/{@link ExitEvaluator} then
 * decide entry/exit on the just-closed daily bar — the batch never re-implements scoring. Pins that a
 * pivot breakout ON THE LAST BAR with expanding volume fires an entry, a flat-volume breakout does
 * not, and an 8%-underwater position exits on the protective stop.
 */
class MinerviniSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal PIVOT = new BigDecimal("150");

  private static StrategyDefinition vcp() throws IOException {
    try (InputStream in =
        MinerviniSwingEngineTest.class.getResourceAsStream("/minervini-strategies/minervini-vcp.yaml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
    }
  }

  @Test
  void entryFiresOnPivotBreakoutWithExpandingVolume() throws IOException {
    List<EngineCandle> series = craft(3_000L); // 3x volume on the final (breakout) bar
    IndicatorBank bank =
        MinerviniSwingEngine.buildBank(vcp(), "TESTCO", series, PIVOT, BigDecimal.ZERO, false);

    Optional<EntryEvaluator.Evaluation> eval =
        EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);

    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("the last bar breaks out above the 150 pivot on 3x volume").isTrue();
  }

  @Test
  void entryBlockedWhenTheBreakoutLacksVolume() throws IOException {
    List<EngineCandle> series = craft(1_000L); // flat volume on the breakout bar
    IndicatorBank bank =
        MinerviniSwingEngine.buildBank(vcp(), "TESTCO", series, PIVOT, BigDecimal.ZERO, false);

    Optional<EntryEvaluator.Evaluation> eval =
        EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);

    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("crossover fires but the vol>1.2 gate blocks it").isFalse();
  }

  @Test
  void protectiveStopExitsAnEightPercentUnderwaterPosition() throws IOException {
    // A held position entered at 152; the series then closes at 135 (11% underwater, below the 8% stop).
    List<EngineCandle> series = craftDecline();
    IndicatorBank bank =
        MinerviniSwingEngine.buildBank(vcp(), "TESTCO", series, BigDecimal.ZERO, BigDecimal.ZERO, false);

    Optional<ExitEvaluator.ExitDecision> exit =
        ExitEvaluator.evaluate(
            vcp(), bank,
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("152"), 0),
            series.size() - 1);

    assertThat(exit).isPresent();
    assertThat(exit.get().type()).isEqualTo("stop_loss");
  }

  /** 25 daily bars: a rise 100→149, a consolidation 146–148 below the 150 pivot, then a breakout to 152 on the LAST bar. */
  private static List<EngineCandle> craft(long breakoutVolume) {
    double[] tail = {146, 148, 146, 148, 147}; // consolidation below the pivot
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(bar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(19 + i, tail[i], 1_000L));
    }
    bars.add(bar(24, 152.0, breakoutVolume)); // the breakout bar (crossover above 150) — LAST bar
    return bars;
  }

  /** 25 daily bars flat at ~150 then a slide to 135 on the last bar (an underwater held position). */
  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 23; d++) {
      bars.add(bar(d, 150.0, 1_000L));
    }
    bars.add(bar(24, 135.0, 1_000L));
    return bars;
  }

  private static EngineCandle bar(int day, double price, long volume) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, p, p, p, p, volume, null);
  }
}
