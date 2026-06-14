package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.PositionSizer;
import in.arthayantra.strategyengine.fills.FillSimulator;
import in.arthayantra.strategyengine.fills.FillSimulator.Fill;
import in.arthayantra.strategyengine.fills.FillSimulator.FillRequest;
import in.arthayantra.strategyengine.fills.FillTiming;
import in.arthayantra.strategyengine.fills.LtpSlippageV1;
import in.arthayantra.strategyengine.fills.ReferencePriceSelector;
import in.arthayantra.strategyengine.fills.Side;
import in.arthayantra.strategyengine.fills.TouchBasis;
import in.arthayantra.strategyengine.golden.GoldenSignalsJson.SignalEvent;
import in.arthayantra.strategyengine.golden.TickwiseGoldenRunner;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import org.springframework.stereotype.Component;

/**
 * The bar-by-bar replay (§D.6). Signal generation runs through {@link TickwiseGoldenRunner} — the
 * SAME live-parity model the golden vectors pin — so replay signals are byte-identical to live by
 * construction (the Phase-30 D15 gate). The replay then pairs entries/exits into trades, applies the
 * shared {@link FillSimulator} (next-bar open / at-close), marks the open position to market each
 * primary bar for the equity curve, and records {@code touch_basis} by detection. Stateless and
 * single-threaded per call for determinism — safe as a shared singleton across the worker pool.
 */
@Component
public class ReplayEngine {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private final FillSimulator fills = new LtpSlippageV1();
  private final ObjectMapper objectMapper;

  /** Wires Jackson (per-trade contributions JSONB). */
  public ReplayEngine(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Replays one strategy over the supplied 1m candles + context series into a full result. */
  public ReplayResult replay(
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primaryOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered) {
    return replay(
        definition, exchange, tradingsymbol, primaryOneMinute, contextCandles, initialEquity, costs,
        oneMinuteCovered, null);
  }

  /**
   * As {@link #replay} but reports replay completion 0..100 to {@code replayProgress} (D17b; nullable),
   * throttled, mapping signal generation onto 0..60% and the trade/equity loop onto 60..100%. The
   * callback is a pure side-channel — trades/equity/metrics are unchanged (the parity gate holds).
   */
  public ReplayResult replay(
      StrategyDefinition definition,
      String exchange,
      String tradingsymbol,
      List<EngineCandle> primaryOneMinute,
      Map<SeriesKey, List<EngineCandle>> contextCandles,
      BigDecimal initialEquity,
      CostConfig costs,
      boolean oneMinuteCovered,
      IntConsumer replayProgress) {

    int barCount = primaryOneMinute.size();
    int sigStep = Math.max(1, barCount / 20);
    IntConsumer signalProgress =
        replayProgress == null || barCount == 0
            ? null
            : i -> {
                if (i % sigStep == 0) {
                  replayProgress.accept(i * 60 / barCount);
                }
              };

    List<SignalEvent> signals =
        new TickwiseGoldenRunner(definition, exchange, tradingsymbol)
            .run(primaryOneMinute, contextCandles, signalProgress);

    FillTiming timing =
        ReferencePriceSelector.defaultFor(
            definition.session().style(), definition.session().fillTiming());
    TouchBasis touchBasis = TouchBasisClassifier.classify(definition, oneMinuteCovered);

    Map<String, Integer> indexByTs = new HashMap<>();
    for (int i = 0; i < primaryOneMinute.size(); i++) {
      indexByTs.put(primaryOneMinute.get(i).bucketStart().toString(), i);
    }

    // pair entry/exit events into directed legs over the 1m series
    List<Leg> legs = legs(signals, indexByTs, primaryOneMinute.size());

    // map each leg to its entry/exit FILL bar
    Map<Integer, Leg> openAt = new HashMap<>();
    Map<Integer, Leg> closeAt = new HashMap<>();
    for (Leg leg : legs) {
      openAt.put(fillBar(leg.entryIndex(), timing, primaryOneMinute.size()), leg);
      closeAt.put(fillBar(leg.exitIndex(), timing, primaryOneMinute.size()), leg);
    }

    BigDecimal cash = initialEquity;
    int posSign = 0;
    long posQty = 0;
    BigDecimal entryPrice = null;
    BigDecimal entryNet = BigDecimal.ZERO;
    java.time.OffsetDateTime entryTs = null;
    int entryFillBar = -1;
    Leg openLeg = null;

    List<Trade> trades = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>();
    List<EquityPoint> drawdownCurve = new ArrayList<>();
    BigDecimal peak = initialEquity;
    long barsInPosition = 0;
    int seq = 0;

    int fillStep = Math.max(1, barCount / 12);
    for (int i = 0; i < primaryOneMinute.size(); i++) {
      if (replayProgress != null && i % fillStep == 0) {
        replayProgress.accept(60 + i * 40 / barCount);
      }
      EngineCandle bar = primaryOneMinute.get(i);

      Leg closing = closeAt.get(i);
      if (closing != null && posSign != 0 && closing == openLeg) {
        BigDecimal ref = reference(primaryOneMinute, closing.exitIndex(), timing, i);
        Side side = posSign > 0 ? Side.SELL : Side.BUY;
        Fill fill = fill(costs, side, posQty, ref);
        cash = cash.add(fill.netValue());
        BigDecimal pnl = entryNet.add(fill.netValue()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal notional = entryPrice.multiply(BigDecimal.valueOf(posQty)).abs();
        BigDecimal pnlPct =
            notional.signum() == 0
                ? BigDecimal.ZERO
                : pnl.multiply(HUNDRED).divide(notional, 6, RoundingMode.HALF_UP);
        trades.add(
            new Trade(
                ++seq,
                posSign > 0 ? Side.BUY : Side.SELL,
                posQty,
                entryTs,
                entryPrice,
                bar.bucketStart(),
                fill.fillPrice(),
                pnl,
                pnlPct,
                closing.exitReason(),
                i - entryFillBar,
                touchBasis,
                contributions(closing.entryBreakdown())));
        posSign = 0;
        posQty = 0;
        openLeg = null;
      }

      Leg opening = openAt.get(i);
      if (opening != null && posSign == 0 && opening.exitIndex() >= opening.entryIndex()) {
        BigDecimal ref = reference(primaryOneMinute, opening.entryIndex(), timing, i);
        long qty = size(definition, cash, ref, costs);
        if (qty > 0) {
          Side side = opening.shortSide() ? Side.SELL : Side.BUY;
          Fill fill = fill(costs, side, qty, ref);
          cash = cash.add(fill.netValue());
          entryNet = fill.netValue();
          posSign = opening.shortSide() ? -1 : 1;
          posQty = qty;
          entryPrice = fill.fillPrice();
          entryTs = bar.bucketStart();
          entryFillBar = i;
          openLeg = opening;
        }
      }

      if (posSign != 0) {
        barsInPosition++;
      }
      BigDecimal equity =
          cash.add(BigDecimal.valueOf(posSign).multiply(BigDecimal.valueOf(posQty)).multiply(bar.close()))
              .setScale(2, RoundingMode.HALF_UP);
      equityCurve.add(new EquityPoint(bar.bucketStart(), equity));
      if (equity.compareTo(peak) > 0) {
        peak = equity;
      }
      BigDecimal dd =
          peak.signum() == 0
              ? BigDecimal.ZERO
              : peak.subtract(equity).multiply(HUNDRED).divide(peak, 6, RoundingMode.HALF_UP);
      drawdownCurve.add(new EquityPoint(bar.bucketStart(), dd));
    }

    BigDecimal finalEquity =
        equityCurve.isEmpty() ? initialEquity : equityCurve.get(equityCurve.size() - 1).equity();
    return new ReplayResult(
        signals,
        trades,
        EquityCurveDownsampler.downsample(equityCurve, 500),
        EquityCurveDownsampler.downsample(drawdownCurve, 500),
        initialEquity,
        finalEquity,
        primaryOneMinute.size(),
        barsInPosition);
  }

  /** A directed entry→exit leg with the 1m indices of the signalling bars. */
  private record Leg(
      boolean shortSide,
      int entryIndex,
      int exitIndex,
      String exitReason,
      in.arthayantra.strategyengine.eval.ScoreBreakdown entryBreakdown) {}

  private static List<Leg> legs(List<SignalEvent> signals, Map<String, Integer> indexByTs, int bars) {
    List<Leg> legs = new ArrayList<>();
    SignalEvent openEntry = null;
    for (SignalEvent ev : signals) {
      if ("EXIT".equals(ev.direction())) {
        if (openEntry != null) {
          legs.add(
              new Leg(
                  "SHORT".equals(openEntry.direction()),
                  indexByTs.getOrDefault(openEntry.timestamp(), 0),
                  indexByTs.getOrDefault(ev.timestamp(), bars - 1),
                  "signal_exit",
                  openEntry.breakdown()));
          openEntry = null;
        }
      } else if (openEntry == null) {
        openEntry = ev;
      }
    }
    if (openEntry != null) {
      // open at end → forced close at the last bar
      legs.add(
          new Leg(
              "SHORT".equals(openEntry.direction()),
              indexByTs.getOrDefault(openEntry.timestamp(), 0),
              bars - 1,
              "end_of_data",
              openEntry.breakdown()));
    }
    return legs;
  }

  private Fill fill(CostConfig costs, Side side, long qty, BigDecimal reference) {
    return fills.simulate(
        new FillRequest(
            side,
            qty,
            costs.lotSize(),
            reference,
            costs.instrumentClass(),
            costs.tickSize(),
            null,
            costs.slippage(),
            costs.brokerage(),
            costs.fees()));
  }

  private static long size(
      StrategyDefinition definition, BigDecimal equity, BigDecimal price, CostConfig costs) {
    return PositionSizer.size(
        definition.sizing(), new PositionSizer.Inputs(equity, price, null, costs.lotSize()));
  }

  private static BigDecimal reference(
      List<EngineCandle> bars, int signalIndex, FillTiming timing, int fillBar) {
    if (timing == FillTiming.AT_CLOSE) {
      return bars.get(signalIndex).close();
    }
    return fillBar < bars.size() ? bars.get(fillBar).open() : bars.get(bars.size() - 1).close();
  }

  private static int fillBar(int signalIndex, FillTiming timing, int bars) {
    return timing == FillTiming.AT_CLOSE ? signalIndex : Math.min(signalIndex + 1, bars - 1);
  }

  private ObjectNode contributions(in.arthayantra.strategyengine.eval.ScoreBreakdown breakdown) {
    ObjectNode node = objectMapper.createObjectNode();
    if (breakdown != null) {
      breakdown
          .indicators()
          .forEach(
              ind ->
                  node.put(
                      ind.alias(),
                      ind.contribution() == null ? null : ind.contribution().toPlainString()));
    }
    return node;
  }
}
