package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DecisionTraceCollectorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ReplayEngine engine = new ReplayEngine(MAPPER);

  @Test
  void classifiesEveryPrimaryDecisionBarWithoutChangingReplayResults() throws Exception {
    Fixture fixture = fixture(false);
    ReplayResult untraced = replay(fixture, null);
    DecisionTraceCollector collector = new DecisionTraceCollector(MAPPER);
    ReplayResult traced = replay(fixture, collector);

    assertThat(traced.signals()).isEqualTo(untraced.signals());
    assertThat(traced.trades()).isEqualTo(untraced.trades());
    assertThat(collector.rows()).extracting(DecisionTraceCollector.Trace::reason)
        .contains("entered", "position_open");
    Set<String> reasons =
        collector.rows().stream()
            .map(DecisionTraceCollector.Trace::reason)
            .collect(Collectors.toSet());
    assertThat(reasons)
        .containsAnyOf("gate_fail", "composite_below_threshold");
    assertThat(collector.rows()).extracting(DecisionTraceCollector.Trace::reason)
        .contains("not_evaluable");
    assertThat(collector.rows().stream().mapToInt(DecisionTraceCollector.Trace::bars).sum())
        .isEqualTo(fixture.candles().size());
  }

  @Test
  void classifiesWantedEntriesBlockedByTheSessionWindow() throws Exception {
    Fixture fixture = fixture(true);
    DecisionTraceCollector collector = new DecisionTraceCollector(MAPPER);

    replay(fixture, collector);

    assertThat(collector.rows()).extracting(DecisionTraceCollector.Trace::reason)
        .contains("session_window")
        .doesNotContain("entered");
  }

  private ReplayResult replay(Fixture fixture, DecisionTraceCollector collector) {
    return engine.replay(
        fixture.definition(),
        "NSE",
        "NIFTY 50",
        fixture.candles(),
        Map.<SeriesKey, List<EngineCandle>>of(),
        new BigDecimal("1000000"),
        CostConfig.defaults(),
        true,
        null,
        collector);
  }

  private static Fixture fixture(boolean closedSession) throws Exception {
    Path root = goldenRoot();
    JsonNode config =
        StrategyDocuments.parse(
                Files.readString(
                    root.resolve("strategies/ema-crossover.yaml"), StandardCharsets.UTF_8))
            .config()
            .deepCopy();
    if (closedSession) {
      ObjectNode window = MAPPER.createObjectNode().put("from", "00:00").put("to", "00:01");
      ((ObjectNode) config.path("risk").path("session")).set("window", window);
    }
    StrategyDefinition definition = StrategyCompiler.compile(config);
    List<EngineCandle> candles = new ArrayList<>();
    for (int day = 1; day <= 5; day++) {
      Path candle = root.resolve("candles/NSE_NIFTY50_1m_day" + day + ".csv");
      try (BufferedReader reader = Files.newBufferedReader(candle, StandardCharsets.UTF_8)) {
        candles.addAll(GoldenCandleCsv.parse(reader));
      }
    }
    return new Fixture(definition, candles);
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

  private record Fixture(StrategyDefinition definition, List<EngineCandle> candles) {}
}
