package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The D15 headline gate from the backtest side (§D.8): the replay engine, driving the SAME engine
 * JAR as live, reproduces the FROZEN golden signal vectors byte-identically — proving replay ==
 * live. Reuses the committed engine golden fixtures (candles + strategy YAMLs + expected JSON)
 * directly off disk. Also pins replay determinism: two runs ⇒ identical signals AND trades.
 */
class BacktestParityTest {

  private static final String[] FEATURES = {
    "ema-crossover", "optional-indicator-activation", "btst-preclose", "exit-intrabar",
    "context-series", "signal-exit-volume", "stop-points", "trailing-points"
  };
  private static final String[] PRIMARY_DAYS = {
    "NSE_NIFTY50_1m_day1.csv", "NSE_NIFTY50_1m_day2.csv", "NSE_NIFTY50_1m_day3.csv",
    "NSE_NIFTY50_1m_day4.csv", "NSE_NIFTY50_1m_day5.csv"
  };
  private static final String[] CONTEXT_DAYS = {
    "NSE_INDIAVIX_1m_day1.csv", "NSE_INDIAVIX_1m_day2.csv", "NSE_INDIAVIX_1m_day3.csv",
    "NSE_INDIAVIX_1m_day4.csv", "NSE_INDIAVIX_1m_day5.csv"
  };

  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @TestFactory
  Stream<DynamicTest> replaySignalsMatchTheFrozenGoldenVectors() {
    return Stream.of(FEATURES)
        .map(
            feature ->
                DynamicTest.dynamicTest(
                    feature,
                    () -> {
                      Fixture fx = load(feature);
                      ReplayResult first = replay(fx);
                      ReplayResult second = replay(fx);

                      String firstJson =
                          GoldenSignalsJson.write(feature + ".yaml", fx.candleFiles(), first.signals());
                      String secondJson =
                          GoldenSignalsJson.write(feature + ".yaml", fx.candleFiles(), second.signals());

                      assertThat(secondJson)
                          .as("two replays serialize byte-identically (determinism)")
                          .isEqualTo(firstJson);
                      assertThat(second.trades())
                          .as("two replays produce the identical trade list")
                          .isEqualTo(first.trades());

                      Path expected =
                          goldenRoot().resolve("expected/" + feature + ".signals.json");
                      assertThat(firstJson)
                          .as("replay signals byte-match the frozen golden (replay == live)")
                          .isEqualTo(Files.readString(expected, StandardCharsets.UTF_8));
                    }));
  }

  private ReplayResult replay(Fixture fx) {
    return engine.replay(
        fx.definition(), "NSE", "NIFTY 50", fx.primary(), fx.contexts(),
        new BigDecimal("1000000"), CostConfig.defaults(), true);
  }

  private record Fixture(
      StrategyDefinition definition,
      List<EngineCandle> primary,
      Map<SeriesKey, List<EngineCandle>> contexts,
      List<String> candleFiles) {}

  private static Fixture load(String feature) throws IOException {
    Path root = goldenRoot();
    String yaml =
        Files.readString(root.resolve("strategies/" + feature + ".yaml"), StandardCharsets.UTF_8);
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());

    List<EngineCandle> primary = new ArrayList<>();
    List<String> candleFiles = new ArrayList<>();
    for (String day : PRIMARY_DAYS) {
      primary.addAll(readCandles(root.resolve("candles/" + day)));
      candleFiles.add(day);
    }
    Map<SeriesKey, List<EngineCandle>> contexts = new LinkedHashMap<>();
    if (definition.indicators().stream().anyMatch(spec -> spec.instrument() != null)) {
      List<EngineCandle> vix = new ArrayList<>();
      for (String day : CONTEXT_DAYS) {
        vix.addAll(readCandles(root.resolve("candles/" + day)));
        candleFiles.add(day);
      }
      contexts.put(new SeriesKey("NSE", "INDIA VIX", "1m"), vix);
    }
    return new Fixture(definition, primary, contexts, candleFiles);
  }

  private static List<EngineCandle> readCandles(Path file) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return GoldenCandleCsv.parse(reader);
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
