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
 *
 * <p>{@code slippageMultiplier} is the request-level cost-stress knob (EVO §3.2.5): the effective
 * slippage the {@code FillSimulator} computes is scaled by it at fill construction. It defaults to
 * {@code 1} (both factories), which is byte-identical to the pre-stress behaviour — an absent
 * {@code stressOverrides} request field never widens a fill. Only stressed re-runs (a fresh run id,
 * never a golden input) carry a multiplier &gt; 1.
 */
public record CostConfig(
    InstrumentClass instrumentClass,
    BigDecimal tickSize,
    long lotSize,
    Slippage slippage,
    Brokerage brokerage,
    Fees fees,
    BigDecimal slippageMultiplier) {

  /** A plain equity proxy with the per-class slippage fallback and default statutory fees. */
  public static CostConfig defaults() {
    return new CostConfig(
        InstrumentClass.EQUITY,
        new BigDecimal("0.05"),
        1,
        Slippage.NONE,
        new Brokerage(null, new BigDecimal("0.03")),
        Fees.DEFAULTS,
        BigDecimal.ONE);
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
        Fees.DEFAULTS,
        BigDecimal.ONE);
  }

  /**
   * A copy with the cost-stress {@code slippageMultiplier} applied (EVO §3.2.5). A {@code null} or
   * {@code 1} multiplier returns an unstressed config — the parity path stays byte-identical.
   */
  public CostConfig withSlippageMultiplier(BigDecimal multiplier) {
    BigDecimal m = multiplier == null ? BigDecimal.ONE : multiplier;
    return new CostConfig(instrumentClass, tickSize, lotSize, slippage, brokerage, fees, m);
  }
}
