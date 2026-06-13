package in.arthayantra.backtest.replay;

import in.arthayantra.strategyengine.fills.FillSimulator.Brokerage;
import in.arthayantra.strategyengine.fills.FillSimulator.Fees;
import in.arthayantra.strategyengine.fills.FillSimulator.Slippage;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import java.math.BigDecimal;

/**
 * The fill/cost configuration a replay applies via the shared {@code FillSimulator} — the {@code
 * costs} block resolved against an instrument class, tick and lot size. Defaults give a credit-free
 * equity proxy (5 bps slippage fallback, statutory fees from {@link FeeConstants}).
 */
public record CostConfig(
    InstrumentClass instrumentClass,
    BigDecimal tickSize,
    long lotSize,
    Slippage slippage,
    Brokerage brokerage,
    Fees fees) {

  /** A plain equity proxy with the per-class slippage fallback and default statutory fees. */
  public static CostConfig defaults() {
    return new CostConfig(
        InstrumentClass.EQUITY,
        new BigDecimal("0.05"),
        1,
        Slippage.NONE,
        new Brokerage(null, new BigDecimal("0.03")),
        Fees.DEFAULTS);
  }
}
