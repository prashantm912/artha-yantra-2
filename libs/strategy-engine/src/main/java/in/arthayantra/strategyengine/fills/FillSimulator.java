package in.arthayantra.strategyengine.fills;

import java.math.BigDecimal;

/**
 * The fill model as a port in the shared {@code strategy-engine} JAR (§D.11 / Q1), consumed
 * identically by backtest replay (Phase 30) and the paper ledger (Stage F) — a second fill
 * implementation anywhere outside this JAR is the explicit FAIL condition, because paper and
 * backtest P&amp;L would then diverge by construction. The shipped implementation is
 * {@link LtpSlippageV1}: {@code fill = referencePrice ± slippage}, no partial fills, deterministic,
 * with the FULL cost model (brokerage legs + slippage + the optional statutory fee schedule).
 */
public interface FillSimulator {

  /** Prices one order into a deterministic {@link Fill}. */
  Fill simulate(FillRequest request);

  /**
   * Slippage spec: at most one of {@code ticks} / {@code bps} (the schema enforces the XOR; this is
   * defended again here). Both null ⇒ the per-class fallback applies.
   */
  record Slippage(Integer ticks, BigDecimal bps) {

    /** No explicit slippage — the per-instrument-class fallback decides. */
    public static final Slippage NONE = new Slippage(null, null);

    /** Validates the XOR. */
    public Slippage {
      if (ticks != null && bps != null) {
        throw new IllegalArgumentException("slippage: at most one of ticks / bps may be set");
      }
    }

    /** Integer-tick slippage. */
    public static Slippage ticks(int ticks) {
      return new Slippage(ticks, null);
    }

    /** Basis-points slippage. */
    public static Slippage bps(BigDecimal bps) {
      return new Slippage(null, bps);
    }
  }

  /**
   * Brokerage spec. {@code perLotInr} prices options (and is the flat leg futures min against);
   * {@code pctPerSide} prices equities (and the percentage leg for futures). Either may be null when
   * not applicable to the instrument class.
   */
  record Brokerage(BigDecimal perLotInr, BigDecimal pctPerSide) {}

  /**
   * Optional statutory fee-rate overrides; any null field falls back to {@link FeeConstants}.
   * {@link #DEFAULTS} uses the pinned schedule for every leg.
   */
  record Fees(
      BigDecimal stt, BigDecimal exchangeTxn, BigDecimal gst, BigDecimal stamp, BigDecimal sebi) {

    /** The pinned current schedule for every leg. */
    public static final Fees DEFAULTS = new Fees(null, null, null, null, null);
  }

  /**
   * One order to price. {@code qty} is units (already lot-rounded by the sizer); {@code lotSize}
   * gives the per-lot brokerage its lot count. {@code quotedSpread} (nullable) feeds the options
   * fallback {@code max(1 tick, half spread)}.
   */
  record FillRequest(
      Side side,
      long qty,
      long lotSize,
      BigDecimal referencePrice,
      InstrumentClass instrumentClass,
      BigDecimal tickSize,
      BigDecimal quotedSpread,
      Slippage slippage,
      Brokerage brokerage,
      Fees fees) {}

  /** The itemized statutory cost legs, each exact to the paisa. */
  record CostBreakdown(
      BigDecimal brokerage,
      BigDecimal stt,
      BigDecimal exchangeTxn,
      BigDecimal gst,
      BigDecimal stamp,
      BigDecimal sebi,
      BigDecimal total) {}

  /**
   * The priced fill. {@code netValue} is the signed cash impact: a BUY is {@code -(turnover +
   * costs)}, a SELL is {@code +(turnover - costs)} — the equity-curve delta the replay applies.
   */
  record Fill(
      BigDecimal fillPrice,
      BigDecimal slippageApplied,
      BigDecimal turnover,
      CostBreakdown costs,
      BigDecimal netValue) {}
}
