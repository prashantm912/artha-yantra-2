package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * B11 / P1-3 candle-path exit-reason attribution: the close-basis event-driven replay must persist
 * the REAL exit reason ({@code stop_loss}/{@code trailing_stop}/{@code take_profit}/{@code
 * square_off}/{@code time_stop}/{@code signal_exit}) that {@link
 * in.arthayantra.strategyengine.eval.ExitEvaluator} fired — not the old universal {@code
 * signal_exit}. Drives the SAME frozen golden fixtures {@link BacktestParityTest} uses (covered
 * path, so the P1-10 bar_hl_worstof overlay stays inert), so the assertion is on the plain
 * default candle path exactly as it persists to {@code backtest_trades.exit_reason}.
 */
class ExitReasonAttributionTest {

  // feature YAML -> the exit reasons its trades may legitimately carry (from its exit_rules). Every
  // fixture here is BALANCED (entry count == EXIT-event count), so no position is open at end and
  // every trade closes on a real ExitEvaluator decision (the open-at-end end_of_data case is covered
  // separately by ema-crossover below).
  private static final Map<String, Set<String>> EXPECTED =
      Map.of(
          "signal-exit-volume", Set.of("signal_exit"),
          "stop-points", Set.of("stop_loss", "take_profit"),
          "trailing-points", Set.of("trailing_stop"),
          "trailing-indicator", Set.of("trailing_stop"));

  private static final String[] PRIMARY_DAYS = {
    "NSE_NIFTY50_1m_day1.csv", "NSE_NIFTY50_1m_day2.csv", "NSE_NIFTY50_1m_day3.csv",
    "NSE_NIFTY50_1m_day4.csv", "NSE_NIFTY50_1m_day5.csv"
  };

  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());

  @TestFactory
  Stream<DynamicTest> tradesCarryTheRealExitReason() {
    return EXPECTED.entrySet().stream()
        .map(
            e ->
                DynamicTest.dynamicTest(
                    e.getKey(),
                    () -> {
                      List<Trade> trades = replay(e.getKey());
                      assertThat(trades)
                          .as("fixture %s produces at least one closed trade to attribute", e.getKey())
                          .isNotEmpty();
                      assertThat(trades)
                          .allSatisfy(
                              t ->
                                  assertThat(t.exitReason())
                                      .as(
                                          "%s trade #%d exit reason is the real ExitEvaluator type",
                                          e.getKey(), t.seq())
                                      .isIn(e.getValue()));
                    }));
  }

  /**
   * Direct guard against the B11 regression: before the fix, a trailing-stop strategy's exits were
   * all recorded as {@code signal_exit}. Assert the trailing fixtures NEVER carry {@code signal_exit}.
   */
  @Test
  void trailingExitsAreNeverMisattributedAsSignalExit() throws IOException {
    for (String feature : new String[] {"trailing-points", "trailing-indicator"}) {
      assertThat(replay(feature))
          .as("%s trades attribute trailing_stop, not the old universal signal_exit", feature)
          .isNotEmpty()
          .allSatisfy(t -> assertThat(t.exitReason()).isEqualTo("trailing_stop"));
    }
  }

  /**
   * A signal-driven exit is attributed {@code signal_exit} while a position still open when data runs
   * out honestly keeps {@code end_of_data} (NOT mapped to a strategy exit reason it never fired). The
   * ema-crossover fixture emits one crossunder EXIT then leaves the re-entry open at the last bar.
   */
  @Test
  void closedLegKeepsRealReasonAndOpenAtEndKeepsEndOfData() throws IOException {
    assertThat(replay("ema-crossover"))
        .extracting(Trade::exitReason)
        .containsExactly("signal_exit", "end_of_data");
  }

  /**
   * Direct pin on every {@link ReplayEngine#mapExitReason} branch — including the ones no golden
   * fixture may exercise: the {@code scaled_exit → take_profit} collapse (a sell-into-strength tier),
   * the {@code square_off}/{@code time_stop} pass-throughs, and the null/unknown defensive fallback
   * to {@code signal_exit}. Guards the persisted {@code backtest_trades.exit_reason} enum contract.
   */
  @Test
  void mapExitReasonCoversEveryExitDecisionType() {
    assertThat(ReplayEngine.mapExitReason("stop_loss")).isEqualTo("stop_loss");
    assertThat(ReplayEngine.mapExitReason("trailing_stop")).isEqualTo("trailing_stop");
    assertThat(ReplayEngine.mapExitReason("take_profit")).isEqualTo("take_profit");
    assertThat(ReplayEngine.mapExitReason("square_off")).isEqualTo("square_off");
    assertThat(ReplayEngine.mapExitReason("time_stop")).isEqualTo("time_stop");
    assertThat(ReplayEngine.mapExitReason("signal_exit")).isEqualTo("signal_exit");
    // a sell-into-strength scaled tier is a profit take → collapses into the DoD's 6-value enum
    assertThat(ReplayEngine.mapExitReason("scaled_exit")).isEqualTo("take_profit");
    // defensive: a null (entry event) or an unrecognised type never NPEs — falls back to signal_exit
    assertThat(ReplayEngine.mapExitReason(null)).isEqualTo("signal_exit");
    assertThat(ReplayEngine.mapExitReason("something_new")).isEqualTo("signal_exit");
  }

  /**
   * Direct plumbing pin (B11): {@link ReplayEngine#legs} reads the EXIT event's {@code exitType}
   * side-channel and maps it onto the paired leg's persisted exit reason. Covers the exit types no
   * covered golden fixture reaches at the leg/Trade layer — the session/Manas {@code square_off}, a
   * {@code scaled_exit} profit tier, and {@code time_stop} — plus a pre-side-channel EXIT event
   * ({@code exitType == null}) collapsing to {@code signal_exit}, all without crafted price action.
   */
  @Test
  void legsMapTheExitEventTypeOntoTheLegReason() {
    assertThat(legReasonFor("square_off")).isEqualTo("square_off");
    assertThat(legReasonFor("scaled_exit")).isEqualTo("take_profit");
    assertThat(legReasonFor("time_stop")).isEqualTo("time_stop");
    assertThat(legReasonFor("stop_loss")).isEqualTo("stop_loss");
    assertThat(legReasonFor(null)).isEqualTo("signal_exit"); // pre-side-channel EXIT event
  }

  /** Pairs a LONG entry with a single full-close EXIT event carrying {@code exitType}. */
  private static String legReasonFor(String exitType) {
    List<EngineCandle> bars =
        List.of(
            barAt("2026-07-03T09:15:00+05:30"),
            barAt("2026-07-03T09:16:00+05:30"),
            barAt("2026-07-03T09:17:00+05:30"));
    SignalEvent entry =
        new SignalEvent("2026-07-03T09:15:00+05:30", "NSE", "NIFTY 50", "LONG", null, null, null);
    SignalEvent exit =
        new SignalEvent(
            "2026-07-03T09:17:00+05:30", "NSE", "NIFTY 50", "EXIT", null, null, null,
            BigDecimal.ONE, exitType);
    var legs = ReplayEngine.legs(List.of(entry, exit), new BarIndexResolver(bars), bars.size());
    assertThat(legs).hasSize(1);
    return legs.get(0).exitReason();
  }

  private static EngineCandle barAt(String iso) {
    return new EngineCandle(
        OffsetDateTime.parse(iso), new BigDecimal("100"), new BigDecimal("101"),
        new BigDecimal("99"), new BigDecimal("100.5"), 10L, null);
  }

  private List<Trade> replay(String feature) throws IOException {
    Path root = goldenRoot();
    String yaml =
        Files.readString(root.resolve("strategies/" + feature + ".yaml"), StandardCharsets.UTF_8);
    StrategyDefinition definition =
        StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
    List<EngineCandle> primary = new ArrayList<>();
    for (String day : PRIMARY_DAYS) {
      primary.addAll(readCandles(root.resolve("candles/" + day)));
    }
    // covered path (oneMinuteCovered=true): the bar_hl_worstof overlay is inert, so exits come
    // purely from the tick-wise ExitEvaluator event stream — the default candle path B11 describes.
    return engine
        .replay(
            definition, "NSE", "NIFTY 50", primary, new LinkedHashMap<>(),
            new BigDecimal("1000000"), CostConfig.defaults(), true)
        .trades();
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
