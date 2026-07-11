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
  // Concrete type (not the FillSimulator port) so the fill call can pass the cost-stress
  // slippageMultiplier — a backtest-only knob kept off the shared paper/live fill port.
  private final LtpSlippageV1 fills = new LtpSlippageV1();
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

    // pair entry/exit events into directed legs over the 1m series
    List<Leg> legs = legs(signals, new BarIndexResolver(primaryOneMinute), primaryOneMinute.size());

    // map each entry to its OPEN fill bar (opener leg only) and each partial close to its exit bar
    Map<Integer, Leg> openAt = new HashMap<>();
    Map<Integer, List<Leg>> closeAt = new HashMap<>();
    for (Leg leg : legs) {
      if (leg.opensPosition()) {
        openAt.put(fillBar(leg.entryIndex(), timing, primaryOneMinute.size()), leg);
      }
      closeAt
          .computeIfAbsent(
              fillBar(leg.exitIndex(), timing, primaryOneMinute.size()), k -> new ArrayList<>())
          .add(leg);
    }

    BigDecimal cash = initialEquity;
    int posSign = 0;
    long posQty = 0;
    long originalQty = 0;
    long remainingQty = 0;
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

      List<Leg> closes = closeAt.get(i);
      if (closes != null) {
        for (Leg closing : closes) {
          if (posSign == 0) {
            break;
          }
          // only close a leg that belongs to the CURRENTLY-OPEN position — a stale/colliding leg from
          // a different (never-opened) entry must NOT close this position (parity-safety: an un-scaled
          // position is never closed by another position's leg). Partial legs share the opener's
          // entryIndex, so all of a scaled position's tiers still fire.
          if (openLeg == null || closing.entryIndex() != openLeg.entryIndex()) {
            continue;
          }
          // a scaled tier closes qty_pct of the ORIGINAL, FLOORED to a whole lot (so partial + final
          // legs are both valid lot multiples and a tiny position rides to the final leg); the final
          // leg takes the exact remainder. An un-scaled strategy has one closesPosition leg →
          // closeQty = remainingQty = the whole position (unchanged path).
          long lot = Math.max(1L, costs.lotSize());
          long rawQty =
              BigDecimal.valueOf(originalQty)
                  .multiply(closing.qtyFraction())
                  .setScale(0, RoundingMode.DOWN)
                  .longValueExact();
          long closeQty =
              closing.closesPosition() ? remainingQty : Math.min(remainingQty, rawQty - (rawQty % lot));
          if (closeQty <= 0) {
            continue;
          }
          BigDecimal ref = reference(primaryOneMinute, closing.exitIndex(), timing, i);
          Side side = posSign > 0 ? Side.SELL : Side.BUY;
          Fill fill = fill(costs, side, closeQty, ref);
          cash = cash.add(fill.netValue());
          // pro-rate the entry cost to this slice (entryNet is the fill net of the full original qty)
          BigDecimal sliceEntryNet =
              entryNet
                  .multiply(BigDecimal.valueOf(closeQty))
                  .divide(BigDecimal.valueOf(originalQty), 10, RoundingMode.HALF_UP);
          BigDecimal pnl = sliceEntryNet.add(fill.netValue()).setScale(2, RoundingMode.HALF_UP);
          BigDecimal notional = entryPrice.multiply(BigDecimal.valueOf(closeQty)).abs();
          BigDecimal pnlPct =
              notional.signum() == 0
                  ? BigDecimal.ZERO
                  : pnl.multiply(HUNDRED).divide(notional, 6, RoundingMode.HALF_UP);
          trades.add(
              new Trade(
                  ++seq,
                  posSign > 0 ? Side.BUY : Side.SELL,
                  closeQty,
                  entryTs,
                  entryPrice,
                  bar.bucketStart(),
                  fill.fillPrice(),
                  pnl,
                  pnlPct,
                  closing.exitReason(),
                  i - entryFillBar,
                  touchBasis,
                  contributions(closing.entryBreakdown()),
                  exchange,
                  tradingsymbol,
                  closing.stopLoss(),
                  closing.takeProfit()));
          remainingQty -= closeQty;
          posQty = remainingQty;
          if (remainingQty <= 0 || closing.closesPosition()) {
            posSign = 0;
            posQty = 0;
            remainingQty = 0;
          }
        }
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
          originalQty = qty;
          remainingQty = qty;
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

  /**
   * A directed close leg with the 1m indices of the signalling bars + entry protective levels.
   * {@code qtyFraction} is the fraction of the ORIGINAL position this leg closes (1 = a whole close,
   * the common case); {@code opensPosition} = the first leg of an entry (it triggers the open);
   * {@code closesPosition} = this leg closes the remainder (so the fractional-qty rounding residual
   * is absorbed here). An un-scaled strategy has exactly one leg per entry: opens + closes with
   * fraction 1 — byte-identical to before.
   */
  record Leg(
      boolean shortSide,
      int entryIndex,
      int exitIndex,
      String exitReason,
      in.arthayantra.strategyengine.eval.ScoreBreakdown entryBreakdown,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      BigDecimal qtyFraction,
      boolean opensPosition,
      boolean closesPosition) {}

  static List<Leg> legs(List<SignalEvent> signals, BarIndexResolver resolver, int bars) {
    List<Leg> legs = new ArrayList<>();
    SignalEvent openEntry = null;
    int entryIndex = -1;
    BigDecimal cumFraction = BigDecimal.ZERO;
    boolean opener = true;
    for (SignalEvent ev : signals) {
      if ("EXIT".equals(ev.direction())) {
        if (openEntry != null && entryIndex >= 0) {
          // an unresolvable exit force-closes at the last bar (the end_of_data convention)
          int exitIndex = resolver.atOrAfter(ev.timestamp());
          BigDecimal frac = ev.qtyFraction() == null ? BigDecimal.ONE : ev.qtyFraction();
          cumFraction = cumFraction.add(frac);
          // close exactly when cumulative reaches the whole position — matches the runner's
          // applyExit boundary (remaining.signum() <= 0), so replay legs never diverge from the events.
          boolean closes = cumFraction.compareTo(BigDecimal.ONE) >= 0;
          legs.add(
              new Leg(
                  "SHORT".equals(openEntry.direction()),
                  entryIndex,
                  exitIndex >= 0 ? exitIndex : bars - 1,
                  "signal_exit",
                  openEntry.breakdown(),
                  openEntry.stopLoss(),
                  openEntry.takeProfit(),
                  frac,
                  opener,
                  closes));
          opener = false;
          if (closes) {
            openEntry = null;
            entryIndex = -1;
            cumFraction = BigDecimal.ZERO;
            opener = true;
          }
        }
      } else if (openEntry == null) {
        // an entry with NO bar at/after its timestamp cannot be priced — drop the leg loudly rather
        // than fabricate a bar-0 trade (audit replay-legs-silent-index0-fallback).
        int idx = resolver.atOrAfter(ev.timestamp());
        if (idx >= 0) {
          openEntry = ev;
          entryIndex = idx;
          cumFraction = BigDecimal.ZERO;
          opener = true;
        }
      }
    }
    if (openEntry != null && entryIndex >= 0) {
      // open (or partially open) at end → forced close of the remainder at the last bar
      legs.add(
          new Leg(
              "SHORT".equals(openEntry.direction()),
              entryIndex,
              bars - 1,
              "end_of_data",
              openEntry.breakdown(),
              openEntry.stopLoss(),
              openEntry.takeProfit(),
              BigDecimal.ONE.subtract(cumFraction).max(BigDecimal.ZERO),
              opener,
              true));
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
            costs.fees()),
        costs.slippageMultiplier());
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
