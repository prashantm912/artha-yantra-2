package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MV-6.2: {@code session.style=swing} — a swing strategy holds a position across multiple daily
 * sessions and is NEVER force-closed by the intraday {@code square_off}. Proven three ways over one
 * crafted multi-day 1m fixture (bars run past 15:12 so an intraday square-off WOULD fire):
 *
 * <ol>
 *   <li>a 1d-primary swing strategy enters once and holds to the end (entry, zero exits) — the real
 *       Minervini use case;
 *   <li>an intraday 1h strategy on the SAME bars squares off (an EXIT appears) — the baseline;
 *   <li>a swing 1h strategy (same primary + {@code square_off}, only {@code style} differs) does NOT
 *       square off (zero exits) — isolating the {@code style=swing} guard from the timeframe.
 * </ol>
 */
class SwingSessionTest {

  private static final List<EngineCandle> CANDLES = craftFiveDays();

  @Test
  void swingDailyPrimaryHoldsAcrossSessionsWithoutSquareOff() {
    List<GoldenSignalsJson.SignalEvent> events = run(swingDaily());
    assertThat(entries(events)).as("swing enters").isGreaterThanOrEqualTo(1);
    assertThat(exits(events)).as("swing holds across days — never squared off").isZero();
  }

  @Test
  void intradayHourlySquaresOffPastTheClock() {
    List<GoldenSignalsJson.SignalEvent> events = run(intradayHourly());
    assertThat(entries(events)).as("intraday enters").isGreaterThanOrEqualTo(1);
    assertThat(exits(events)).as("intraday is force-closed at/after 15:12").isGreaterThanOrEqualTo(1);
  }

  @Test
  void swingStyleSuppressesSquareOffOnAnyPrimary() {
    // Same 1h primary + same square_off as the intraday baseline — only style=swing differs.
    List<GoldenSignalsJson.SignalEvent> events = run(swingHourly());
    assertThat(entries(events)).as("swing enters").isGreaterThanOrEqualTo(1);
    assertThat(exits(events)).as("style=swing suppresses the square-off even on a 1h primary").isZero();
  }

  private static List<GoldenSignalsJson.SignalEvent> run(String yaml) {
    StrategyDefinition definition = StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
    return new TickwiseGoldenRunner(definition, "NSE", "NIFTY 50").run(CANDLES, Map.of());
  }

  private static long entries(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> !"EXIT".equals(e.direction())).count();
  }

  private static long exits(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream().filter(e -> "EXIT".equals(e.direction())).count();
  }

  /** Five IST weekday sessions, four 1m bars each (incl. a 15:29 bar past 15:12), gently rising. */
  private static List<EngineCandle> craftFiveDays() {
    List<EngineCandle> bars = new ArrayList<>();
    LocalDate day = LocalDate.of(2026, 6, 1); // Monday
    for (int d = 0; d < 5; d++) {
      double base = 20000 + d * 100.0;
      bars.add(bar(day, LocalTime.of(9, 15), base));
      bars.add(bar(day, LocalTime.of(10, 15), base + 10));
      bars.add(bar(day, LocalTime.of(13, 15), base + 20));
      bars.add(bar(day, LocalTime.of(15, 29), base + 30));
      day = day.plusDays(1);
    }
    return bars;
  }

  private static EngineCandle bar(LocalDate d, LocalTime t, double px) {
    BigDecimal p = BigDecimal.valueOf(px);
    return new EngineCandle(OffsetDateTime.of(d, t, EngineSeries.IST), p, p, p, p, 1_000L);
  }

  // --- inline strategies (SMA(2) scored + always-true gate → fires once warmed; benign far stop) ---

  private static String swingDaily() {
    return strategy("1d", "swing", null);
  }

  private static String intradayHourly() {
    return strategy("1h", "intraday", "15:12");
  }

  private static String swingHourly() {
    return strategy("1h", "swing", "15:12");
  }

  private static String strategy(String primary, String style, String squareOff) {
    String session =
        squareOff == null
            ? "  session: { style: " + style + " }"
            : "  session: { style: " + style + ", square_off: \"" + squareOff + "\" }";
    String template =
        """
        schema: strategy-schema/v1
        id: swing-session-%s-%s
        name: "Swing Session %s %s"
        version: 1.0.0
        universe:
          mode: explicit
          instruments:
            - { exchange: NSE, tradingsymbol: "NIFTY 50" }
        timeframes: { primary: %s }
        indicators:
          - { name: SMA, alias: sma2, timeframe: %s, params: { period: 2 }, weight: 1.0,
              normalize: { type: linear, from: 0, to: 100000 } }
        entry_rules:
          direction: long
          gate: { all: [ "close > 0" ] }
          scoring: { threshold: 0.0 }
        exit_rules:
          - { type: stop_loss, params: { basis: index_points, value: 100000 } }
        risk:
          position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
          max_positions: 1
        %s
        """;
    return template.formatted(primary, style, primary, style, primary, primary, session);
  }
}
