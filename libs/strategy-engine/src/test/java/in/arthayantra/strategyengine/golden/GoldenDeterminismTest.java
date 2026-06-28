package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.io.IOException;
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
 * The Phase 23 determinism goldens (C-2.19): for every strategies/<feature>.yaml, the tick-wise
 * run over the FROZEN candle fixtures must byte-match expected/<feature>.signals.json — and two
 * independent runs must byte-match each other. Green here pins DETERMINISM only (P1-1 caveat:
 * a deterministic engine reproduces an overfit strategy exactly — never read these as evidence
 * a strategy generalizes). Regenerate-once mode: {@code -Dgolden.generate=true} writes the
 * expected files; committed outputs are then frozen.
 */
class GoldenDeterminismTest {

  private static final String[] FEATURES = {
    "ema-crossover", "optional-indicator-activation", "btst-preclose", "exit-intrabar",
    "context-series", "signal-exit-volume"
  };
  private static final String[] PRIMARY_DAYS = {
    "NSE_NIFTY50_1m_day1.csv", "NSE_NIFTY50_1m_day2.csv", "NSE_NIFTY50_1m_day3.csv",
    "NSE_NIFTY50_1m_day4.csv", "NSE_NIFTY50_1m_day5.csv"
  };
  private static final String[] CONTEXT_DAYS = {
    "NSE_INDIAVIX_1m_day1.csv", "NSE_INDIAVIX_1m_day2.csv", "NSE_INDIAVIX_1m_day3.csv",
    "NSE_INDIAVIX_1m_day4.csv", "NSE_INDIAVIX_1m_day5.csv"
  };

  @TestFactory
  Stream<DynamicTest> goldenVectorsPinDeterminism() {
    return Stream.of(FEATURES)
        .map(
            feature ->
                DynamicTest.dynamicTest(
                    feature,
                    () -> {
                      String first = runOnce(feature);
                      String second = runOnce(feature);
                      assertThat(second)
                          .as("two independent tick-wise runs must serialize byte-identically")
                          .isEqualTo(first);

                      Path expected = goldenRoot().resolve("expected/" + feature + ".signals.json");
                      if (Boolean.getBoolean("golden.generate")) {
                        Files.writeString(expected, first, StandardCharsets.UTF_8);
                        return;
                      }
                      assertThat(Files.exists(expected))
                          .as("expected fixture committed: %s", expected)
                          .isTrue();
                      assertThat(first)
                          .as("tick-wise output must byte-match the FROZEN expected fixture")
                          .isEqualTo(Files.readString(expected, StandardCharsets.UTF_8));
                    }));
  }

  private static String runOnce(String feature) throws IOException {
    Path root = goldenRoot();
    String yaml =
        Files.readString(root.resolve("strategies/" + feature + ".yaml"), StandardCharsets.UTF_8);
    StrategyDefinition definition = StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());

    List<EngineCandle> primary = new ArrayList<>();
    List<String> candleFiles = new ArrayList<>();
    for (String day : PRIMARY_DAYS) {
      primary.addAll(readCandles(root.resolve("candles/" + day)));
      candleFiles.add(day);
    }
    Map<SeriesKey, List<EngineCandle>> contexts = new LinkedHashMap<>();
    boolean needsContext =
        definition.indicators().stream().anyMatch(spec -> spec.instrument() != null);
    if (needsContext) {
      List<EngineCandle> vix = new ArrayList<>();
      for (String day : CONTEXT_DAYS) {
        vix.addAll(readCandles(root.resolve("candles/" + day)));
        candleFiles.add(day);
      }
      contexts.put(new SeriesKey("NSE", "INDIA VIX", "1m"), vix);
    }

    List<GoldenSignalsJson.SignalEvent> events =
        new TickwiseGoldenRunner(definition, "NSE", "NIFTY 50").run(primary, contexts);
    return GoldenSignalsJson.write(feature + ".yaml", candleFiles, events);
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
