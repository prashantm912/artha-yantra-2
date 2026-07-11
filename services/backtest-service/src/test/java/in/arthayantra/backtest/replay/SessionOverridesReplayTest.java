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
 * EVO §7.1.2 request-level fill-timing pin at the candle {@link ReplayEngine} level. The reconcile
 * re-sim rebuilds the compiled definition's session via {@link BacktestRunner#withFillTiming}, so
 * ReplayEngine (which resolves timing from {@code session().fillTiming()}) fills at the pinned timing:
 * an {@code at_close} pin fills at the signal bar's close, a {@code next_open} pin at the next bar's
 * open — so a paired entry fills at a DIFFERENT price under each. A {@code null} pin returns the same
 * definition instance (the byte-identical default path). Same run-up fixture as {@code
 * StressOverridesReplayTest} so the run produces trades.
 */
class SessionOverridesReplayTest {

  private static final LocalDate DAY0 = LocalDate.of(2026, 6, 1);
  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @Test
  void nullOverrideReturnsTheSameDefinitionInstance() {
    StrategyDefinition def = scaled();
    assertThat(BacktestRunner.withFillTiming(def, null)).isSameAs(def);
  }

  @Test
  void pinRewritesOnlyFillTimingAndPreservesEverythingElse() {
    StrategyDefinition def = scaled();
    StrategyDefinition pinned = BacktestRunner.withFillTiming(def, "at_close");

    assertThat(pinned.session().fillTiming()).isEqualTo("at_close");
    // every other session knob + top-level field is copied verbatim
    assertThat(pinned.session().style()).isEqualTo(def.session().style());
    assertThat(pinned.session().windowFrom()).isEqualTo(def.session().windowFrom());
    assertThat(pinned.session().windowTo()).isEqualTo(def.session().windowTo());
    assertThat(pinned.session().squareOff()).isEqualTo(def.session().squareOff());
    assertThat(pinned.session().preCloseAt()).isEqualTo(def.session().preCloseAt());
    assertThat(pinned.session().exitIntrabar()).isEqualTo(def.session().exitIntrabar());
    assertThat(pinned.session().expiryDayAllowed()).isEqualTo(def.session().expiryDayAllowed());
    assertThat(pinned.session().expiryWindowFrom()).isEqualTo(def.session().expiryWindowFrom());
    assertThat(pinned.session().expiryWindowTo()).isEqualTo(def.session().expiryWindowTo());
    assertThat(pinned.session().touchBasis()).isEqualTo(def.session().touchBasis());
    assertThat(pinned.id()).isEqualTo(def.id());
    assertThat(pinned.version()).isEqualTo(def.version());
    assertThat(pinned.primaryTimeframe()).isEqualTo(def.primaryTimeframe());
    assertThat(pinned.additionalTimeframes()).isEqualTo(def.additionalTimeframes());
    assertThat(pinned.direction()).isEqualTo(def.direction());
    assertThat(pinned.indicators()).isEqualTo(def.indicators());
    assertThat(pinned.gate()).isEqualTo(def.gate());
    assertThat(pinned.scoring()).isEqualTo(def.scoring());
    assertThat(pinned.exitRules()).isEqualTo(def.exitRules());
    assertThat(pinned.sizing()).isEqualTo(def.sizing());
  }

  @Test
  void atCloseAndNextOpenFillTheSameEntryAtDifferentPrices() {
    ReplayResult atClose = run(BacktestRunner.withFillTiming(scaled(), "at_close"));
    ReplayResult nextOpen = run(BacktestRunner.withFillTiming(scaled(), "next_open"));

    // signals are timing-agnostic, so both books open the same entry; only the fill bar/price differs.
    assertThat(atClose.trades()).isNotEmpty();
    assertThat(nextOpen.trades()).isNotEmpty();
    assertThat(atClose.trades().get(0).entryPrice())
        .as("at_close fills at the signal-bar close; next_open at the following bar's open")
        .isNotEqualByComparingTo(nextOpen.trades().get(0).entryPrice());
  }

  private ReplayResult run(StrategyDefinition definition) {
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(new SeriesKey("NSE", "MINERVINI_PIVOT", "1d"), pivot(150.0));
    return engine.replay(
        definition, "NSE", "NIFTY 50", craftRun(), contexts, new BigDecimal("1000000"),
        CostConfig.defaults(), true);
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
