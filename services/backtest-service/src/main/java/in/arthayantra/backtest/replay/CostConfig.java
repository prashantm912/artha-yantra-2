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

  /**
   * The OPTION analog of {@link #defaults()} for the premium-as-primary leg (Part 2): the per-class
   * option slippage fallback ({@code max(1 tick, half-spread)} → 1 tick at ₹0.05 with no quoted
   * spread), ₹20/lot flat brokerage, and the pinned statutory schedule (STT-on-sell, exchange txn,
   * GST, stamp-on-buy, SEBI). Mirrors the candle path's {@code CostConfig.defaults()} so premium-leg
   * fills stay paisa-parity with the shared {@code FillSimulator}.
   */
  public static CostConfig optionDefaults(long lotSize) {
    return new CostConfig(
        InstrumentClass.OPTION,
        new BigDecimal("0.05"),
        lotSize,
        Slippage.NONE,
        new Brokerage(new BigDecimal("20"), null),
        Fees.DEFAULTS);
  }
}
