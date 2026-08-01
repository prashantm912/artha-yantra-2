package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.BtTrade;
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
 * The M7 (#128) swing exit-equivalence CHARACTERIZATION fixture, Manas half: runs the LIVE {@link
 * ExitEvaluator}'s {@code atr_multiple}+{@code cap_pct} stop-loss basis and {@link
 * ManasAroraSwingBacktest}'s own hand-rolled {@code initialStop} over the SAME {@code
 * contracts/fixtures/swing-exit-equivalence.json} scenarios (agreement), then characterizes the M6
 * entry-bar divergence named in the batch scoping (docs/signal-analysis/2026-08-02-e4-128-batch-
 * scoping.md): the exit FORMULA agrees on paper, but the live engine and the deep sim disagree on
 * WHETHER a same-bar entry+exit can ever be observed. This is a characterization, not a fix — M6
 * stays HOLD-tier; nothing here changes live or backtest behaviour.
 */
class ManasSwingExitEquivalenceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int WARMUP_BARS = 25; // comfortably > atrPeriod(20) for the ATR to settle

  private static JsonNode fixture() throws Exception {
    // surefire's working dir is the module dir; the fixture lives at the repo root
    Path p = Path.of("..", "..", "contracts", "fixtures", "swing-exit-equivalence.json");
    return MAPPER.readTree(Files.readString(p));
  }

  @Test
  void atrCappedStopAgreesWithTheLiveExitEvaluatorAcrossEveryScenario() throws Exception {
    JsonNode family = fixture().path("families").path("manas");
    ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest();

    for (JsonNode sc : family.path("scenarios")) {
      String name = sc.path("name").asText();
      BigDecimal entryPrice = new BigDecimal(sc.path("entryPrice").asText());
      double trueRange = Double.parseDouble(sc.path("trueRange").asText());
      StrategyDefinition def = definitionWith(sc.path("config"));
      List<String> postEntryCloses = new ArrayList<>();
      sc.path("closes").forEach(n -> postEntryCloses.add(n.asText()));
      // closes[0] IS the entry bar's own close (== entryPrice); the warm-up already ends there.
      assertThat(postEntryCloses.get(0)).isEqualTo(entryPrice.toPlainString());

      EngineSeries warmupOnly = warmupSeries(entryPrice, trueRange);
      int entryIndex = warmupOnly.size() - 1;
      ExitEvaluator.Position position =
          new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, entryIndex);

      // 1) the entry-time LEVEL both sides compute for this doctrine.
      BigDecimal expectedStop =
          new BigDecimal(sc.path("expectedLevels").path("stopLoss").asText());
      ExitEvaluator.EntryLevels liveLevels =
          ExitEvaluator.entryLevels(def, bank(def, warmupOnly), position);
      assertThat(liveLevels.stopLoss()).as(name + " (live level)").isEqualByComparingTo(expectedStop);
      // ManasAroraSwingBacktest's OWN formula, called directly (the production call site): entry −
      // min(atrMult×ATR, capPct%×entry). atr is the constant true range this fixture engineered.
      double simStop = sim.initialStop(entryPrice.doubleValue(), trueRange);
      assertThat(simStop).as(name + " (sim level)").isCloseTo(
          expectedStop.doubleValue(), org.assertj.core.data.Offset.offset(1e-9));

      // 2) the hit/no-hit outcome over the post-entry closes, live side (ATR is pinned at ENTRY —
      // ExitEvaluator's own documented contract — so appending arbitrary post-entry bars is safe).
      EngineSeries full = appendCloses(warmupOnly, postEntryCloses.subList(1, postEntryCloses.size()));
      IndicatorBank fullBank = bank(def, full);
      JsonNode expect = sc.path("expect");
      int expectedBarOffset = expect.path("barOffset").asInt(); // offset from the entry bar
      Optional<ExitEvaluator.ExitDecision> liveExit = Optional.empty();
      int liveOffset = -1;
      for (int i = entryIndex + 1; i < full.size(); i++) {
        Optional<ExitEvaluator.ExitDecision> d = ExitEvaluator.evaluate(def, fullBank, position, i);
        if (d.isPresent()) {
          liveExit = d;
          liveOffset = i - entryIndex;
          break;
        }
      }
      assertThat(liveExit).as(name + " (live)").isPresent();
      assertThat(liveExit.get().type()).as(name + " (live type)").isEqualTo("stop_loss");
      assertThat(liveOffset).as(name + " (live bar offset)").isEqualTo(expectedBarOffset);

      // 3) the DEEP SIM's own outcome, via the SAME comparison simulateSetup's per-lot loop makes
      // (ManasAroraSwingBacktest.stopBreached — not a hand-copied `<=`, the actual production
      // predicate at the call site) — closes a gap the level-only check above cannot: a change to
      // the comparison OPERATOR (e.g. `<=` to `<`) moves no level, so it would leave the live-only
      // hit/no-hit check above untouched while genuinely breaking the deep sim's outcome.
      List<String> postEntryOnly = postEntryCloses.subList(1, postEntryCloses.size());
      int simOffset = -1;
      for (int i = 0; i < postEntryOnly.size(); i++) {
        if (ManasAroraSwingBacktest.stopBreached(Double.parseDouble(postEntryOnly.get(i)), simStop)) {
          simOffset = i + 1; // +1: postEntryOnly[0] is bar offset 1 from the entry bar
          break;
        }
      }
      assertThat(simOffset).as(name + " (sim bar offset, real stopBreached predicate)")
          .isEqualTo(expectedBarOffset);
    }
  }

  @Test
  void entryBarDivergenceLiveCanExitOnTheEntryBarButTheDeepSimStructurallyCannot() throws Exception {
    JsonNode m6 = fixture().path("entryBarDivergence");
    List<String> closes = new ArrayList<>();
    m6.path("closes").forEach(n -> closes.add(n.asText()));
    int entryIndex = m6.path("entryIndex").asInt();
    BigDecimal entryPrice = new BigDecimal(closes.get(entryIndex));
    StrategyDefinition def = definitionWith(m6.path("config"));
    EngineSeries series = plainSeries(closes);
    ExitEvaluator.Position position =
        new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, entryPrice, entryIndex);

    // 1) LIVE has no entry-bar guard: SwingBatchEngine.exitPass evaluates the SAME index as the
    // entry when a lot was opened this exact run (lotsAsOf admits a same-day lot; the "M6" finding).
    Optional<ExitEvaluator.ExitDecision> liveOnEntryBar =
        ExitEvaluator.evaluate(def, bank(def, series), position, entryIndex);
    assertThat(liveOnEntryBar).as("live fires on the entry bar itself").isPresent();
    assertThat(liveOnEntryBar.get().type()).isEqualTo(m6.path("expect").path("reason").asText());

    // 2) the DEEP SIM's own formula, called directly, AGREES with live's decision — the exit MATH
    // is not the divergence.
    double[] closeArr = closes.stream().mapToDouble(Double::parseDouble).toArray();
    double[] sma10 = new double[closeArr.length];
    java.util.Arrays.fill(sma10, Double.NaN); // parabolic clause inert; isolates the fast-move check
    ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest();
    String simReasonIfCalledOnEntryBar =
        sim.positionExit(entryIndex, closeArr, sma10, false, 0.0);
    assertThat(simReasonIfCalledOnEntryBar)
        .as("ManasAroraSwingBacktest.positionExit computes the SAME fast-move decision as live's"
            + " square_off, if it were ever invoked on the entry bar")
        .isEqualTo("FAST_MOVE");

    // 3) but the REAL, gate-passing simulate() engine structurally NEVER calls positionExit (or the
    // per-lot initial-stop check) on the bar a lot was just opened — simulateSetup's entry branch
    // `continue`s before any exit check runs the same iteration. This is independent synthetic
    // data (a real breakout+gate pass), not the 4-bar square_off fixture above: the invariant it
    // proves — every closed trade's entry and exit bars differ — holds regardless of which exit
    // rule eventually fires.
    List<BtTrade> trades = realGatedBreakoutTrades();
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

  /**
   * Same Stage-2-uptrend-then-breakout shape as ManasAroraSwingBacktestTest.breakoutSeries(), but
   * the breakout bar itself (268) is deliberately a +35%+ move off 3 bars back (179 -> 250), so it
   * ALSO satisfies the fast-move square-off threshold in isolation. This makes the barsHeld
   * assertion below load-bearing: a naive "the breakout bar itself never trips an exit condition"
   * fixture would pass even if the entry-bar `continue` guard were removed, since nothing would
   * ever fire same-bar to observe. With this data, an entry-bar exit check WOULD fire FAST_MOVE
   * immediately (verified by temporarily deleting the guard and confirming this test reddens) —
   * the unmutated sim still closes one bar later (269, a crash to 197 trips the ATR stop).
   */
  private static List<BtTrade> realGatedBreakoutTrades() {
    // minBaseWeeks=0 (matches ManasAroraSwingBacktestTest's own fixture, #1214): VcpDetector.<init>
    // now throws for any positive floor (M40 tripwire — the measurement is latent, not fixed).
    VcpDetector vcp = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, 0, 65);
    ConsolidationBreakout breakout = new ConsolidationBreakout(2.5, 10, 40, 25);
    ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest();
    LocalDate entryFrom = LocalDate.of(2020, 1, 1).plusDays(264);
    List<DailyBar> bars = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      bars.add(dailyBar(i, 80.0 + 105.0 * i / 255.0, 1_000));
    }
    double[] base = {182, 179, 183, 180, 182, 178, 183, 181, 182, 179, 183, 180};
    for (int i = 0; i < base.length; i++) {
      bars.add(dailyBar(256 + i, base[i], 1_000));
    }
    double[] tail = {250, 197, 190, 178, 170};
    long[] vol = {3_000, 1_000, 1_000, 1_000, 1_000};
    for (int i = 0; i < tail.length; i++) {
      bars.add(dailyBar(268 + i, tail[i], vol[i]));
    }
    return sim.simulate("TESTCO", bars, vcp, breakout, entryFrom);
  }

  private static DailyBar dailyBar(int day, double price, long volume) {
    LocalDate d = LocalDate.of(2020, 1, 1).plusDays(day);
    return new DailyBar(d, price, price, price, price, volume);
  }

  /** {@code count} flat bars at {@code entryPrice} with a CONSTANT true range of {@code range}. */
  private static EngineSeries warmupSeries(BigDecimal entryPrice, double range) {
    List<EngineCandle> candles = new ArrayList<>();
    OffsetDateTime start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST);
    BigDecimal half = BigDecimal.valueOf(range / 2.0);
    for (int i = 0; i < WARMUP_BARS; i++) {
      candles.add(
          new EngineCandle(
              start.plusDays(i), entryPrice, entryPrice.add(half), entryPrice.subtract(half),
              entryPrice, 1_000L));
    }
    return EngineSeries.of(new SeriesKey("NSE", "TESTCO", "1d"), candles);
  }

  /** Appends flat (high==low==close) bars after an existing series — ATR is pinned at entry, so
   * these later bars' true range does not matter to the atr_multiple stop this fixture exercises. */
  private static EngineSeries appendCloses(EngineSeries base, List<String> closes) {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < base.size(); i++) {
      candles.add(base.candle(i));
    }
    OffsetDateTime next = base.candle(base.size() - 1).bucketStart().plusDays(1);
    for (String c : closes) {
      BigDecimal price = new BigDecimal(c);
      candles.add(new EngineCandle(next, price, price, price, price, 1_000L));
      next = next.plusDays(1);
    }
    return EngineSeries.of(base.key(), candles);
  }

  private static EngineSeries plainSeries(List<String> closes) {
    List<EngineCandle> candles = new ArrayList<>();
    OffsetDateTime start = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, IST);
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
