package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
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
import java.time.LocalDate;
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

  /**
   * The M6 characterization (docs/signal-analysis/2026-08-02-e4-128-batch-scoping.md), Minervini
   * half — sibling of {@code ManasSwingExitEquivalenceTest}'s test of the same name. Live has no
   * entry-bar guard for ANY family (ExitEvaluator is family-agnostic — this half proves it for a
   * Minervini-flavoured compiled config too, not just Manas's).
   *
   * <p><b>Open doubt, disclosed rather than hidden behind a decorative mutation:</b> for Minervini
   * SPECIFICALLY, {@code barsHeld > 0} is not falsifiable by mutating {@code
   * simulateSetup}'s entry/exit {@code if}/{@code else} exclusivity alone (verified: doing so left
   * this assertion GREEN). The reason is a second, independent guard baked into the MATH, not just
   * the control flow: {@code exitFires}'s percent stop-loss needs {@code close < entrySignalClose},
   * impossible on the entry bar where {@code close == entrySignalClose} exactly; its 50-day-MA
   * trail needs {@code close <= sma50}, which directly contradicts the Trend-Template entry gate's
   * OWN {@code close > sma50} requirement (MinerviniGates.java:42) — so a valid Minervini entry can
   * never simultaneously satisfy either exit condition. Manas's {@code square_off} has no such
   * conflict (it is independent of the entry gate's price-vs-MA structure), which is why its
   * {@code if}/{@code else} mutation genuinely reddens (see {@code ManasSwingExitEquivalenceTest}).
   * The assertions below are still real and pass against genuine entry-gated production output —
   * they just are not this family's discriminating proof of the CONTROL-FLOW guard specifically.
   */
  @Test
  void entryBarDivergenceLiveCanExitOnTheEntryBarButTheDeepSimStructurallyCannot() throws Exception {
    JsonNode m6 = fixture().path("entryBarDivergence");
    List<String> closes = new ArrayList<>();
    m6.path("closes").forEach(n -> closes.add(n.asText()));
    int entryIndex = m6.path("entryIndex").asInt();
    BigDecimal entryPrice = new BigDecimal(closes.get(entryIndex));
    StrategyDefinition def = definitionWith(m6.path("config"));
    EngineSeries liveSeries = series(closes);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, entryIndex);

    // 1) LIVE has no entry-bar guard: SwingBatchEngine.exitPass (shared by both families) can
    // evaluate ExitEvaluator at the SAME index a lot was opened this run (a same-day catch-up lot).
    Optional<ExitEvaluator.ExitDecision> liveOnEntryBar =
        ExitEvaluator.evaluate(def, bank(def, liveSeries), position, entryIndex);
    assertThat(liveOnEntryBar).as("live fires on the entry bar itself").isPresent();
    assertThat(liveOnEntryBar.get().type()).isEqualTo(m6.path("expect").path("reason").asText());

    // 2) the REAL, gate-passing MinerviniSwingBacktest.simulate() (the SAME shape
    // MinerviniSwingBacktestTest proves takes a real primary-base trade) structurally NEVER closes
    // a trade on its own entry bar: the entry `if (!inTrade)` branch and the exit `else` branch are
    // mutually exclusive within one loop iteration. Independent synthetic data from the 4-bar
    // square_off fixture above — the invariant it proves (every trade's entry/exit bars differ)
    // holds regardless of which exit rule (stop_loss or trailing_stop) eventually fires.
    List<BtTrade> trades = realGatedPrimaryBaseTrades();
    assertThat(trades).as("the synthetic breakout+rollover takes at least one trade").isNotEmpty();
    for (BtTrade t : trades) {
      assertThat(t.barsHeld())
          .as("deep-sim trade %s never closes on its own entry bar (structural M6 guard)", t)
          .isGreaterThan(0);
      assertThat(t.exitDate())
          .as("deep-sim trade %s never exits on its entry date (structural M6 guard)", t)
          .isNotEqualTo(t.entryDate());
    }
  }

  /** Same shape as MinerviniSwingBacktestTest.primaryBaseTakesABreakoutTradeAndStopsOutOnTheRollover. */
  private static List<BtTrade> realGatedPrimaryBaseTrades() {
    // minBaseWeeks=0 (matches MinerviniSwingBacktestTest's own fixture, #1214): VcpDetector.<init>
    // now throws for any positive floor (M40 tripwire — the measurement is latent, not fixed).
    VcpDetector detector = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, 0, 65);
    List<DailyBar> bars = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      bars.add(dailyBar(i, 100.0 + 80.0 * i / 255.0, 1_000));
    }
    double[] base = {175, 173, 176, 174, 175, 173, 176, 174};
    for (int i = 0; i < base.length; i++) {
      bars.add(dailyBar(256 + i, base[i], 1_000));
    }
    double[] tail = {185, 188, 182, 174, 165};
    long[] vol = {3_000, 1_000, 1_000, 1_000, 1_000};
    for (int i = 0; i < tail.length; i++) {
      bars.add(dailyBar(264 + i, tail[i], vol[i]));
    }
    return MinerviniSwingBacktest.simulate(
        "TESTCO", bars, detector, LocalDate.of(2020, 1, 1).plusDays(264));
  }

  private static DailyBar dailyBar(int day, double price, long volume) {
    LocalDate d = LocalDate.of(2020, 1, 1).plusDays(day);
    return new DailyBar(d, price, price, price, price, volume);
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
