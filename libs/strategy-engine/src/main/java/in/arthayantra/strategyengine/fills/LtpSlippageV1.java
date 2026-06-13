package in.arthayantra.strategyengine.fills;

import in.arthayantra.strategyengine.indicators.EngineMath;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The shipped {@code ltp_slippage/v1} fill model (§D.11): {@code fill = referencePrice ± slippage},
 * no partial fills, NUMERIC end to end, deterministic. Slippage is {@code slippage_ticks} ⊕
 * {@code slippage_bps}; when neither is set the per-instrument-class fallback applies (equities 5
 * bps; options {@code max(1 tick, half quoted spread)} degrading to 1 tick; futures 1 tick, A9
 * [FP-7]). The cost model is FULL — brokerage legs plus the optional statutory {@code fees}
 * schedule (STT, exchange transaction charge, GST, stamp, SEBI) applied side-aware and exact to the
 * paisa, identically in backtest and the paper ledger.
 */
public final class LtpSlippageV1 implements FillSimulator {

  private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
  private static final BigDecimal TWO = new BigDecimal("2");

  @Override
  public Fill simulate(FillRequest request) {
    BigDecimal slippage = money(slippageAmount(request));
    BigDecimal reference = request.referencePrice();
    BigDecimal fillPrice =
        money(
            request.side() == Side.BUY ? reference.add(slippage) : reference.subtract(slippage));
    BigDecimal turnover = money(fillPrice.multiply(BigDecimal.valueOf(request.qty()), EngineMath.MC));

    CostBreakdown costs = costs(request, turnover);
    BigDecimal signedTurnover = request.side() == Side.SELL ? turnover : turnover.negate();
    BigDecimal netValue = signedTurnover.subtract(costs.total());
    return new Fill(fillPrice, slippage, turnover, costs, netValue);
  }

  private BigDecimal slippageAmount(FillRequest req) {
    Slippage slippage = req.slippage() == null ? Slippage.NONE : req.slippage();
    if (slippage.ticks() != null) {
      return req.tickSize().multiply(BigDecimal.valueOf(slippage.ticks()), EngineMath.MC);
    }
    if (slippage.bps() != null) {
      return bpsAmount(slippage.bps(), req.referencePrice());
    }
    // per-class fallback
    return switch (req.instrumentClass()) {
      case EQUITY -> bpsAmount(FeeConstants.EQUITY_FALLBACK_BPS, req.referencePrice());
      case FUTURE -> req.tickSize().multiply(BigDecimal.valueOf(FeeConstants.FUTURE_FALLBACK_TICKS));
      case OPTION -> optionFallback(req);
    };
  }

  private BigDecimal optionFallback(FillRequest req) {
    BigDecimal oneTick =
        req.tickSize().multiply(BigDecimal.valueOf(FeeConstants.OPTION_FALLBACK_TICKS));
    if (req.quotedSpread() == null) {
      return oneTick;
    }
    BigDecimal halfSpread = req.quotedSpread().divide(TWO, EngineMath.MC);
    return oneTick.max(halfSpread);
  }

  private static BigDecimal bpsAmount(BigDecimal bps, BigDecimal reference) {
    return reference.multiply(bps, EngineMath.MC).divide(TEN_THOUSAND, EngineMath.MC);
  }

  private CostBreakdown costs(FillRequest req, BigDecimal turnover) {
    Fees fees = req.fees() == null ? Fees.DEFAULTS : req.fees();
    BigDecimal brokerage = money(brokerage(req, turnover));
    BigDecimal stt = money(stt(req, fees, turnover));
    BigDecimal exchangeTxn = money(turnover.multiply(txnRate(req, fees), EngineMath.MC));
    BigDecimal sebi = money(turnover.multiply(rate(fees.sebi(), FeeConstants.SEBI), EngineMath.MC));
    BigDecimal stamp = money(stamp(req, fees, turnover));
    BigDecimal gstBase = brokerage.add(exchangeTxn).add(sebi);
    BigDecimal gst = money(gstBase.multiply(rate(fees.gst(), FeeConstants.GST), EngineMath.MC));
    BigDecimal total = brokerage.add(stt).add(exchangeTxn).add(gst).add(stamp).add(sebi);
    return new CostBreakdown(brokerage, stt, exchangeTxn, gst, stamp, sebi, total);
  }

  private BigDecimal brokerage(FillRequest req, BigDecimal turnover) {
    Brokerage spec = req.brokerage();
    if (spec == null) {
      return BigDecimal.ZERO;
    }
    return switch (req.instrumentClass()) {
      case OPTION -> {
        if (spec.perLotInr() == null) {
          yield BigDecimal.ZERO;
        }
        long lots = req.lotSize() <= 0 ? req.qty() : req.qty() / req.lotSize();
        yield spec.perLotInr().multiply(BigDecimal.valueOf(lots), EngineMath.MC);
      }
      case EQUITY -> pctBrokerage(spec, turnover);
      case FUTURE -> {
        BigDecimal pct = pctBrokerage(spec, turnover);
        // A9 [FP-7]: futures brokerage is min-of percentage and the flat per-order cap
        yield pct.signum() == 0 ? FeeConstants.FLAT_BROKERAGE_INR : pct.min(FeeConstants.FLAT_BROKERAGE_INR);
      }
    };
  }

  private static BigDecimal pctBrokerage(Brokerage spec, BigDecimal turnover) {
    if (spec.pctPerSide() == null) {
      return BigDecimal.ZERO;
    }
    return turnover.multiply(spec.pctPerSide(), EngineMath.MC).divide(EngineMath.HUNDRED, EngineMath.MC);
  }

  private static BigDecimal stt(FillRequest req, Fees fees, BigDecimal turnover) {
    return switch (req.instrumentClass()) {
      case OPTION ->
          req.side() == Side.SELL
              ? turnover.multiply(rate(fees.stt(), FeeConstants.STT_OPTION_SELL), EngineMath.MC)
              : BigDecimal.ZERO;
      case FUTURE ->
          req.side() == Side.SELL
              ? turnover.multiply(rate(fees.stt(), FeeConstants.STT_FUTURE_SELL), EngineMath.MC)
              : BigDecimal.ZERO;
      case EQUITY -> turnover.multiply(rate(fees.stt(), FeeConstants.STT_EQUITY), EngineMath.MC);
    };
  }

  private static BigDecimal txnRate(FillRequest req, Fees fees) {
    if (fees.exchangeTxn() != null) {
      return fees.exchangeTxn();
    }
    return switch (req.instrumentClass()) {
      case OPTION -> FeeConstants.TXN_OPTION;
      case FUTURE -> FeeConstants.TXN_FUTURE;
      case EQUITY -> FeeConstants.TXN_EQUITY;
    };
  }

  private static BigDecimal stamp(FillRequest req, Fees fees, BigDecimal turnover) {
    if (req.side() != Side.BUY) {
      return BigDecimal.ZERO; // stamp duty is buy-side only
    }
    BigDecimal rate =
        switch (req.instrumentClass()) {
          case OPTION -> rate(fees.stamp(), FeeConstants.STAMP_OPTION);
          case FUTURE -> rate(fees.stamp(), FeeConstants.STAMP_FUTURE);
          case EQUITY -> rate(fees.stamp(), FeeConstants.STAMP_EQUITY);
        };
    return turnover.multiply(rate, EngineMath.MC);
  }

  private static BigDecimal rate(BigDecimal override, BigDecimal fallback) {
    return override != null ? override : fallback;
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
