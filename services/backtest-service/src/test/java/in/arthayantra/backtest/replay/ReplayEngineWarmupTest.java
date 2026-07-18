package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AY-SL-03: {@link ReplayEngine}'s fold-warmup overload replays a PAST-ONLY warmup prefix ahead of the
 * window so a long-lookback gate warms, while SUPPRESSING every pre-window emission so the trades /
 * signals / equity reflect ONLY the in-window bars.
 *
 * <p>The fixture is a 1m-primary long that fires {@code crossover(px, WEEK52_HIGH(period=30))} on a
 * fresh 30-bar high — a gate that a 20-bar OOS fold can NEVER self-warm (WEEK52_HIGH is null until 30
 * prior bars exist). Bars: 34 flat @100, a WARMUP breakout to 105 (bars 34-39), 5 more flat @105, then
 * an in-window breakout to 110 (bar 45). The 40-bar warmup prefix warms the gate; the boundary is the
 * first in-window bar (bar 40).
 */
class ReplayEngineWarmupTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal EQUITY = new BigDecimal("1000000");
  private static final String EXCHANGE = "NSE";
  private static final String SYMBOL = "TEST";

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: warmup-longlookback
      name: "Warmup Long Lookback"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: "TEST" }
      timeframes: { primary: 1m }
      indicators:
        - { name: SMA, alias: px, timeframe: 1m, params: { period: 1 }, weight: 1.0,
            normalize: { type: linear, from: 0, to: 100000 } }
        - { name: WEEK52_HIGH, alias: hh, timeframe: 1m, params: { period: 30 }, weight: 1.0 }
      entry_rules:
        direction: long
        gate:
          all:
            - crossover: { fast: px, slow: hh }
        scoring: { threshold: 0.0 }
      exit_rules:
        - { type: stop_loss, params: { basis: percent, value: 8 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  private final ReplayEngine engine = new ReplayEngine(new ObjectMapper());
  private final StrategyDefinition definition =
      StrategyCompiler.compile(StrategyDocuments.parse(YAML).config());

  // 60 consecutive 1m bars: [0..39] warmup prefix (a breakout to 105 at bar 34), [40..59] the window
  // (a fresh-high breakout to 110 at bar 45).
  private final List<EngineCandle> allBars = buildBars();
  private final List<EngineCandle> warmupPrefix = allBars.subList(0, 40);
  private final List<EngineCandle> window = allBars.subList(40, 60);
  private final OffsetDateTime boundary = window.get(0).bucketStart();

  private static List<EngineCandle> buildBars() {
    List<EngineCandle> bars = new ArrayList<>(60);
    OffsetDateTime start = OffsetDateTime.of(2026, 1, 5, 9, 15, 0, 0, IST);
    for (int i = 0; i < 60; i++) {
      OffsetDateTime ts = start.plusMinutes(i);
      if (i == 34) {
        bars.add(candle(ts, 100, 105, 100, 105)); // WARMUP breakout (must be suppressed)
      } else if (i >= 35 && i <= 44) {
        bars.add(flat(ts, 105));
      } else if (i == 45) {
        bars.add(candle(ts, 105, 110, 105, 110)); // in-window fresh high (must fire)
      } else if (i >= 46) {
        bars.add(flat(ts, 110));
      } else {
        bars.add(flat(ts, 100)); // 0..33
      }
    }
    return bars;
  }

  private static EngineCandle flat(OffsetDateTime ts, long px) {
    BigDecimal p = BigDecimal.valueOf(px);
    return new EngineCandle(ts, p, p, p, p, 100L);
  }

  private static EngineCandle candle(OffsetDateTime ts, long o, long h, long l, long c) {
    return new EngineCandle(
        ts, BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l),
        BigDecimal.valueOf(c), 100L);
  }

  private ReplayResult cold() {
    return engine.replay(
        definition, EXCHANGE, SYMBOL, window, Map.<SeriesKey, List<EngineCandle>>of(),
        EQUITY, CostConfig.defaults(), true);
  }

  private ReplayResult warm() {
    return engine.replay(
        definition, EXCHANGE, SYMBOL, window, Map.<SeriesKey, List<EngineCandle>>of(),
        EQUITY, CostConfig.defaults(), true, null, null, warmupPrefix);
  }

  @Test
  void longLookbackGateEvaluatesInWindowOnlyWithWarmup() {
    // Cold (the pre-fix behaviour): a 20-bar window can never warm WEEK52_HIGH(30), so the gate is
    // structurally unable to evaluate — zero signals, zero trades.
    ReplayResult cold = cold();
    assertThat(cold.signals()).as("cold fold cannot warm the 30-bar gate").isEmpty();
    assertThat(cold.trades()).isEmpty();

    // Warm: the 40-bar past-only prefix warms the gate, so the in-window fresh high fires.
    ReplayResult warm = warm();
    assertThat(warm.signals()).as("warmup lets the long-lookback gate fire in-window").isNotEmpty();
    assertThat(warm.trades()).isNotEmpty();
  }

  @Test
  void warmupContributesZeroPreBoundaryActivityAndEquityStartsAtBoundary() {
    ReplayResult warm = warm();

    // Every emitted signal is in-window (the bar-34 warmup breakout is suppressed, never emitted).
    for (SignalEvent s : warm.signals()) {
      assertThat(OffsetDateTime.parse(s.timestamp()))
          .as("no signal before the fold boundary")
          .isAfterOrEqualTo(boundary);
    }

    // Every trade is in-window; no warmup position leaks across the boundary.
    for (Trade t : warm.trades()) {
      assertThat(t.entryTs()).as("no trade entry before the fold boundary").isAfterOrEqualTo(boundary);
      if (t.exitTs() != null) {
        assertThat(t.exitTs()).isAfterOrEqualTo(boundary);
      }
    }

    // The equity curve starts AT the boundary and at the untouched initial equity — proving no warmup
    // position (the bar-34 entry) leaked in as an opening mark.
    assertThat(warm.equityCurve()).isNotEmpty();
    assertThat(warm.equityCurve().get(0).ts()).isEqualTo(boundary);
    assertThat(warm.equityCurve().get(0).equity()).isEqualByComparingTo(EQUITY);
    assertThat(warm.initialEquity()).isEqualByComparingTo(EQUITY);
    // totalBars reflects the window only, not the warmup prefix.
    assertThat(warm.totalBars()).isEqualTo(window.size());
  }

  @Test
  void sameFoldReRunIsDeterministic() {
    ReplayResult a = warm();
    ReplayResult b = warm();
    assertThat(b.trades()).isEqualTo(a.trades());
    assertThat(b.equityCurve()).isEqualTo(a.equityCurve());
    assertThat(b.signals()).hasSameSizeAs(a.signals());
    assertThat(b.finalEquity()).isEqualByComparingTo(a.finalEquity());
  }
}
