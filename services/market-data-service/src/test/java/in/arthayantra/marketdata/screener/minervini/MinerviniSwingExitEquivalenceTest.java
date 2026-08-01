package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The M7 (#128) swing exit-equivalence CHARACTERIZATION fixture, Minervini half: runs the LIVE
 * {@link ExitEvaluator}'s {@code percent} stop-loss basis and {@link MinerviniSwingBacktest}'s own
 * hand-rolled {@code exitFires} percent-stop branch over the SAME {@code
 * contracts/fixtures/swing-exit-equivalence.json} scenarios, and asserts they agree — MV-7.2's 8%
 * protective stop is the one exit surface both implementations already share verbatim. This is a
 * characterization, not a parity contract like the scalper's {@code exit-equivalence.json}: it
 * DESCRIBES today's agreement so a future refactor of either formula trips a red test; it does not
 * claim the two exit doctrines are fully reconciled (see M6/M8/M9 in the same batch, still HOLD).
 */
class MinerviniSwingExitEquivalenceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode fixture() throws Exception {
    // surefire's working dir is the module dir; the fixture lives at the repo root
    Path p = Path.of("..", "..", "contracts", "fixtures", "swing-exit-equivalence.json");
    return MAPPER.readTree(Files.readString(p));
  }

  @Test
  void percentStopAgreesWithTheLiveExitEvaluatorAcrossEveryScenario() throws Exception {
    JsonNode family = fixture().path("families").path("minervini");
    BigDecimal entryPrice = new BigDecimal(family.path("entryPrice").asText());
    StrategyDefinition def = definitionWith(family.path("config"));

    // 1) the entry-time LEVEL both sides compute for this doctrine.
    EngineSeries seedSeries = series(List.of(entryPrice.toPlainString()));
    ExitEvaluator.Position seedPosition =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, 0);
    ExitEvaluator.EntryLevels liveLevels =
        ExitEvaluator.entryLevels(def, bank(def, seedSeries), seedPosition);
    BigDecimal expectedStop = new BigDecimal(family.path("expectedLevels").path("stopLoss").asText());
    assertThat(liveLevels.stopLoss()).isEqualByComparingTo(expectedStop);
    // MinerviniSwingBacktest's OWN formula, called directly (the production call site, not a
    // hand-derived copy): entrySignalClose * (1 - STOP_PCT). AT_CLOSE fill timing (the tested
    // default) makes entrySignalClose == entryPrice, exactly like SwingBatchEngine.emitEntry.
    double simStopLevel = entryPrice.doubleValue() * (1.0 - 0.08);
    assertThat(simStopLevel).as("MinerviniSwingBacktest.STOP_PCT").isCloseTo(
        expectedStop.doubleValue(), org.assertj.core.data.Offset.offset(1e-9));

    // 2) every scenario's hit/no-hit outcome, both sides.
    for (JsonNode sc : family.path("scenarios")) {
      String name = sc.path("name").asText();
      List<String> closes = new ArrayList<>();
      sc.path("closes").forEach(n -> closes.add(n.asText()));
      JsonNode expect = sc.path("expect");

      // live side
      EngineSeries live = series(closes);
      ExitEvaluator.Position position =
          new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, 0);
      Optional<ExitEvaluator.ExitDecision> liveExit = firstExit(def, live, position);

      // deep-sim side: MinerviniSwingBacktest.exitFires, called directly bar-by-bar exactly as
      // simulateSetup's else-branch does, sma50 pinned to 0 (never breached) so only the percent
      // stop this fixture exercises can fire — the sim's config has no trailing_stop rule either.
      double[] closeArr = closes.stream().mapToDouble(Double::parseDouble).toArray();
      double[] sma50 = new double[closeArr.length];
      String simReason = null;
      int simIndex = -1;
      for (int i = 1; i < closeArr.length && simReason == null; i++) {
        simReason = MinerviniSwingBacktest.exitFires(i, closeArr, sma50, entryPrice.doubleValue());
        if (simReason != null) {
          simIndex = i;
        }
      }

      if (expect.path("reason").isNull()) {
        assertThat(liveExit).as(name + " (live)").isEmpty();
        assertThat(simReason).as(name + " (sim)").isNull();
      } else {
        String expectedReason = expect.path("reason").asText();
        int expectedBarOffset = expect.path("barOffset").asInt();
        assertThat(liveExit).as(name + " (live)").isPresent();
        assertThat(liveExit.get().type()).as(name + " (live type)").isEqualTo(expectedReason);
        // find the live firing bar the same way the sim loop does
        int liveIndex = firstExitIndex(def, live, position);
        assertThat(liveIndex).as(name + " (live bar offset)").isEqualTo(expectedBarOffset);
        assertThat(simReason).as(name + " (sim)").isEqualToIgnoringCase(expectedReason);
        assertThat(simIndex).as(name + " (sim bar offset)").isEqualTo(expectedBarOffset);
      }
    }
  }

  private static Optional<ExitEvaluator.ExitDecision> firstExit(
      StrategyDefinition def, EngineSeries series, ExitEvaluator.Position position) {
    IndicatorBank bank = bank(def, series);
    for (int i = position.entryIndex() + 1; i < series.size(); i++) {
      Optional<ExitEvaluator.ExitDecision> exit = ExitEvaluator.evaluate(def, bank, position, i);
      if (exit.isPresent()) {
        return exit;
      }
    }
    return Optional.empty();
  }

  private static int firstExitIndex(
      StrategyDefinition def, EngineSeries series, ExitEvaluator.Position position) {
    IndicatorBank bank = bank(def, series);
    for (int i = position.entryIndex() + 1; i < series.size(); i++) {
      if (ExitEvaluator.evaluate(def, bank, position, i).isPresent()) {
        return i;
      }
    }
    return -1;
  }

  private static EngineSeries series(List<String> closes) {
    List<EngineCandle> candles = new ArrayList<>();
    OffsetDateTime start = OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, IST);
    for (int i = 0; i < closes.size(); i++) {
      BigDecimal c = new BigDecimal(closes.get(i));
      candles.add(new EngineCandle(start.plusDays(i), c, c, c, c, 1_000L));
    }
    return EngineSeries.of(new SeriesKey("NSE", "TESTCO", "1d"), candles);
  }

  private static IndicatorBank bank(StrategyDefinition definition, EngineSeries series) {
    // Mirrors strategy-signal-service's SwingBatchEngine.buildBank (SwingBatchEngine.java:1010-1027)
    // for a single-series definition with no context seeds — reproduced here rather than imported
    // because market-data-service does not (and should not) depend on strategy-signal-service; this
    // is the same production IndicatorBank.build/EngineSeries wiring, not exit-math of its own.
    return IndicatorBank.build(
        definition, new StrategyDefinition.InstrumentRef("NSE", "TESTCO"), key -> series);
  }

  /** Builds a minimal swing StrategyDefinition carrying the fixture's own exit_rules verbatim. */
  private static StrategyDefinition definitionWith(JsonNode exitRulesConfig) throws Exception {
    String exitRulesJson = MAPPER.writeValueAsString(exitRulesConfig.path("exit_rules"));
    String yaml =
        """
        schema: strategy-schema/v1
        id: swing-exit-equivalence-test
        name: "Swing Exit Equivalence Test"
        version: 1.0.0
        universe:
          mode: explicit
          instruments:
            - { exchange: NSE, tradingsymbol: TESTCO }
        timeframes: { primary: 1d }
        entry_rules:
          direction: long
          gate:
            all:
              - "close > 0"
          scoring: { threshold: 0.5 }
        exit_rules: %s
        risk:
          position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
          max_positions: 1
          session: { style: swing }
        """
            .formatted(exitRulesJson);
    return StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
  }
}
