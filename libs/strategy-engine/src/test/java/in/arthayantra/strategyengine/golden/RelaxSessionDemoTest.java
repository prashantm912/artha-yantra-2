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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Empirical demonstration of {@code backtest.relax_session} (#358): the SAME windowed strategy on the
 * SAME real NIFTY 1m day, run with the session clock rail OFF then ON, must produce MORE entries when
 * relaxed (the afternoon crossovers a morning-only window blocks are unlocked). Uses the production
 * {@link TickwiseGoldenRunner} path — the exact {@code SessionGate} the backtest's premium replay
 * drives — so the printed counts are the real lever effect, not a mock. NOT a golden (it asserts a
 * relationship, not a frozen vector), so it never gates the byte-identical determinism suite.
 */
class RelaxSessionDemoTest {

  // A morning-session scalper: trade 09:45-13:00, sit out the afternoon. The engine SessionGate (the
  // only thing relax_session touches in replay) enforces this window + square_off; there is NO midday
  // block in replay (that is the live-only scalper gate). So OFF blocks every 13:00-15:29 crossover; ON
  // unlocks them. (The 11:00-13:00 "midday block" lever from #361 is a LIVE gate, not exercised here.)
  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: relax-session-demo
      name: "Relax Session Demo"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: "NIFTY 50" }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
      entry_rules:
        direction: long
        # a deliberately permissive entry (rsi present ⇒ true) + a 1-bar time-stop ⇒ the strategy
        # CHURNS — an entry on (almost) every eligible bar — so the session window's effect is large
        # and visible. This is a LEVER demonstrator, not a tradeable signal.
        gate:
          all:
            - "rsi_1m > 0"
        scoring: { threshold: 0.0 }
      exit_rules:
        - { type: time_stop, params: { max_bars: 1 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session:
          style: intraday
          window: { from: "09:45", to: "13:00" }
          square_off: "13:00"
      """;

  @Test
  void relaxSessionUnlocksAfternoonEntries() throws IOException {
    StrategyDefinition def = StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());
    List<EngineCandle> day = readCandles(goldenRoot().resolve("candles/NSE_NIFTY50_1m_day1.csv"));

    List<String> entriesOff = entryTimes(run(def, day, false));
    List<String> entriesOn = entryTimes(run(def, day, true));

    System.out.println("=== relax_session demonstration — NSE NIFTY 50 1m, 2026-01-05, window 09:45-13:00 ===");
    System.out.println("relax_session=false  -> " + entriesOff.size() + " entries: " + entriesOff);
    System.out.println("relax_session=true   -> " + entriesOn.size() + " entries: " + entriesOn);
    List<String> unlocked = entriesOn.stream().filter(t -> !entriesOff.contains(t)).toList();
    System.out.println("unlocked by relax    -> " + unlocked.size() + " extra entries: " + unlocked);

    // Every off-entry is still taken when relaxed (relax only ADDS), and the afternoon is unlocked.
    assertThat(entriesOn).containsAll(entriesOff);
    assertThat(entriesOn.size())
        .as("relax_session unlocks the 13:00-15:29 entries the morning window blocked")
        .isGreaterThan(entriesOff.size());
    // every unlocked entry is genuinely outside the 09:45-13:00 window.
    assertThat(unlocked).allSatisfy(t -> {
      String hhmm = t.substring(11, 16);
      assertThat(hhmm.compareTo("13:00") >= 0 || hhmm.compareTo("09:45") < 0).isTrue();
    });
  }

  private static List<GoldenSignalsJson.SignalEvent> run(
      StrategyDefinition def, List<EngineCandle> day, boolean relax) {
    return new TickwiseGoldenRunner(def, "NSE", "NIFTY 50")
        .run(day, Map.<SeriesKey, List<EngineCandle>>of(), null, relax);
  }

  /** The IST timestamps of the ENTRY (non-EXIT) events, in order. */
  private static List<String> entryTimes(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream()
        .filter(e -> !"EXIT".equals(e.direction()))
        .map(GoldenSignalsJson.SignalEvent::timestamp)
        .toList();
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
