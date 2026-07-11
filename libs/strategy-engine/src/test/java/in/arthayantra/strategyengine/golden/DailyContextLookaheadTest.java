package in.arthayantra.strategyengine.golden;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P1-9 (audit B7): the daily-context completed-bucket visibility gate. A context bar coarser than the
 * current tick becomes readable only once its bucket END has passed — so an in-progress 1d bar (today's
 * still-forming close) is invisible until the session that closes it, mirroring the live B1 contract
 * (#683). Two parts:
 *
 * <ol>
 *   <li>{@link #predicateEndGatesCoarseContextButIdenticalForOneMinute()} tests
 *       {@link TickwiseGoldenRunner#contextBarComplete} directly — this is the layer P1-9 changed, and
 *       it distinguishes the new END-gate from the old START-gate.
 *   <li>{@link #dailyContextCloseInvisibleIntradayVisibleNextSession()} asserts the
 *       end-to-end contract through the production runner. NB: this holds on BOTH the old and new
 *       {@code advanceContexts} because the downstream {@code IndicatorBank.mappedIndex} independently
 *       maps a cross-timeframe indicator to the last COMPLETED context bucket at the primary bar's
 *       close — so the reachable look-ahead was already gated there. This end-gate hardens the
 *       {@code advanceContexts} layer to match, and is byte-identical on every committed golden (they
 *       carry only 1m / no contexts). NOT a frozen golden — asserts a relationship.
 * </ol>
 */
class DailyContextLookaheadTest {

  private static final LocalDate D0 = LocalDate.of(2026, 6, 1); // session 0
  private static final LocalDate D1 = LocalDate.of(2026, 6, 2); // session 1
  private static final LocalDate D2 = LocalDate.of(2026, 6, 3); // session 2

  @Test
  void predicateEndGatesCoarseContextButIdenticalForOneMinute() {
    // A 1d context bar stamped at D0 00:00 IST (its START). Its bucket END is D1 00:00.
    Instant ctx1dStart = OffsetDateTime.of(D0, LocalTime.MIDNIGHT, EngineSeries.IST).toInstant();

    // Intraday on D0 (the bucket's own session): NOT complete — this is the look-ahead the gate closes.
    Instant d0Open = OffsetDateTime.of(D0, LocalTime.of(9, 15), EngineSeries.IST).toInstant();
    Instant d0Close = OffsetDateTime.of(D0, LocalTime.of(15, 29), EngineSeries.IST).toInstant();
    assertThat(TickwiseGoldenRunner.contextBarComplete(ctx1dStart, "1d", plus1m(d0Open)))
        .as("today's 1d bar is invisible at the session open")
        .isFalse();
    assertThat(TickwiseGoldenRunner.contextBarComplete(ctx1dStart, "1d", plus1m(d0Close)))
        .as("today's 1d bar is still invisible at the session close (bucket not yet ended)")
        .isFalse();

    // The NEXT session (D1): complete — the bucket has ended (D1 00:00), so it is now readable.
    Instant d1Open = OffsetDateTime.of(D1, LocalTime.of(9, 15), EngineSeries.IST).toInstant();
    assertThat(TickwiseGoldenRunner.contextBarComplete(ctx1dStart, "1d", plus1m(d1Open)))
        .as("the completed 1d bar becomes visible from the next session")
        .isTrue();

    // A 1m context is IDENTICAL to the old `start <= barStart` rule (end = start+1m, close = start+1m):
    // visible at its own bar, invisible one minute early. This is why every committed golden (1m/no
    // context) stays byte-identical.
    Instant ctx1mStart = OffsetDateTime.of(D0, LocalTime.of(9, 20), EngineSeries.IST).toInstant();
    assertThat(TickwiseGoldenRunner.contextBarComplete(ctx1mStart, "1m", plus1m(ctx1mStart)))
        .as("a 1m context bar is visible at its own minute (== old start-gate)")
        .isTrue();
    assertThat(
            TickwiseGoldenRunner.contextBarComplete(
                ctx1mStart, "1m", plus1m(ctx1mStart.minusSeconds(60))))
        .as("a 1m context bar is invisible one minute early (== old start-gate)")
        .isFalse();
  }

  @Test
  void dailyContextCloseInvisibleIntradayVisibleNextSession() {
    StrategyDefinition def = StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());

    List<EngineCandle> primary = new ArrayList<>();
    primary.addAll(session(D0));
    primary.addAll(session(D1));
    primary.addAll(session(D2));

    // The signal's own 1d series jumps to 99 (> the gate's 50) on its D1 (2026-06-02) bar.
    Map<SeriesKey, List<EngineCandle>> contexts =
        Map.of(
            new SeriesKey("NSE", "NIFTY 50", "1d"),
            List.of(
                ctxDay(D0.minusDays(1), 10.0), // 05-31 close 10
                ctxDay(D0, 20.0), //             06-01 close 20
                ctxDay(D1, 99.0))); //           06-02 close 99

    List<String> entries =
        entryTimes(new TickwiseGoldenRunner(def, "NSE", "NIFTY 50").run(primary, contexts));

    System.out.println("=== P1-9 daily-context look-ahead — gate dctx>50, 1d[05-31,06-01,06-02]=[10,20,99] ===");
    System.out.println("entries -> " + entries);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0))
        .as("today's daily close is invisible intraday; the >50 context becomes visible next session")
        .startsWith("2026-06-03T09:15");
    assertThat(entries)
        .as("no entry leaks on session D0/D1 — a raw start-gated read would have fired on 2026-06-02")
        .noneMatch(t -> t.startsWith("2026-06-01") || t.startsWith("2026-06-02"));
  }

  // A 1m-primary strategy whose ONLY entry condition is a 1d level (dctx > 50). dctx = SMA(1) on the
  // signal's own 1d series (a coarse CONTEXT grown by advanceContexts). px = SMA(1) on 1m purely to give
  // the composite a valid always-passing score. signal_exit never fires ⇒ exactly one entry.
  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: daily-context-lookahead-demo
      name: "Daily Context Lookahead Demo"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: "NIFTY 50" }
      timeframes: { primary: 1m, additional: [1d] }
      indicators:
        - { name: SMA, alias: px, timeframe: 1m, params: { period: 1 }, weight: 1.0,
            normalize: { type: linear, from: 0, to: 100000 } }
        - { name: SMA, alias: dctx, timeframe: 1d, params: { period: 1 }, weight: 0.0 }
      entry_rules:
        direction: long
        gate:
          all:
            - "dctx > 50"
        scoring: { threshold: 0.0 }
      exit_rules:
        - { type: signal_exit, params: { rule: "dctx < 0" } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  private static Instant plus1m(Instant t) {
    return t.plusSeconds(60);
  }

  private static List<EngineCandle> session(LocalDate day) {
    List<EngineCandle> bars = new ArrayList<>();
    BigDecimal px = BigDecimal.valueOf(20000);
    for (int m = 0; m < 3; m++) {
      bars.add(
          new EngineCandle(
              OffsetDateTime.of(day, LocalTime.of(9, 15 + m), EngineSeries.IST), px, px, px, px, 1_000L));
    }
    return bars;
  }

  private static EngineCandle ctxDay(LocalDate day, double close) {
    BigDecimal c = BigDecimal.valueOf(close);
    return new EngineCandle(
        OffsetDateTime.of(day, LocalTime.MIDNIGHT, EngineSeries.IST), c, c, c, c, 0L);
  }

  private static List<String> entryTimes(List<GoldenSignalsJson.SignalEvent> events) {
    return events.stream()
        .filter(e -> !"EXIT".equals(e.direction()))
        .map(GoldenSignalsJson.SignalEvent::timestamp)
        .toList();
  }
}
