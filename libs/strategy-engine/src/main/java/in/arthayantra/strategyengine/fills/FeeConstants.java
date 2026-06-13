package in.arthayantra.strategyengine.fills;

import java.math.BigDecimal;

/**
 * The single statutory-fee constants file (§D.11 / decisions log §4.3). Rates are the current
 * Zerodha brokerage + NSE / SEBI / CBDT (STT/CTT) / state stamp schedule, <b>pinned at
 * implementation on 2026-06-13</b>. They are deliberately gathered in one place with this
 * source+date note so a future rate change is a one-file edit — refresh per the decisions log when
 * the schedule moves. All rates are fractions (0.001 = 0.10%); applied identically in backtest and
 * the paper ledger via the shared {@code FillSimulator}.
 *
 * <p>Provenance: Zerodha equity/F&amp;O brokerage (₹20-per-order cap / flat), NSE transaction
 * charges, SEBI turnover fee (₹10/crore), CBDT STT post-2024-10-01 derivatives revision, and the
 * Maharashtra stamp-duty buy-side schedule. A flat per-lot default understates premium-proportional
 * charges on options, which is exactly why the {@code fees} schedule exists.
 */
public final class FeeConstants {

  private FeeConstants() {}

  /** Zerodha flat brokerage / per-order cap (₹). Options pay this per lot; futures min-of pct/flat. */
  public static final BigDecimal FLAT_BROKERAGE_INR = new BigDecimal("20.00");

  // ---- STT / CTT (sell-side for derivatives; both sides for equity delivery) ----
  /** Options STT on sell-side premium — 0.10% (post 2024-10-01). */
  public static final BigDecimal STT_OPTION_SELL = new BigDecimal("0.001000");

  /** Futures STT on sell-side notional — 0.02% (post 2024-10-01). */
  public static final BigDecimal STT_FUTURE_SELL = new BigDecimal("0.000200");

  /** Equity delivery STT — 0.10% on BOTH buy and sell. */
  public static final BigDecimal STT_EQUITY = new BigDecimal("0.001000");

  // ---- NSE exchange transaction charges (on turnover / premium) ----
  /** Options exchange transaction charge — 0.03503% on premium. */
  public static final BigDecimal TXN_OPTION = new BigDecimal("0.00035030");

  /** Futures exchange transaction charge — 0.00173% on notional. */
  public static final BigDecimal TXN_FUTURE = new BigDecimal("0.00001730");

  /** Equity exchange transaction charge — 0.00297% on turnover. */
  public static final BigDecimal TXN_EQUITY = new BigDecimal("0.00002970");

  /** GST — 18% on (brokerage + exchange transaction charge + SEBI fee). */
  public static final BigDecimal GST = new BigDecimal("0.18");

  // ---- Stamp duty (BUY side only) ----
  /** Options stamp duty — 0.003% on buy-side premium. */
  public static final BigDecimal STAMP_OPTION = new BigDecimal("0.00003000");

  /** Futures stamp duty — 0.002% on buy-side notional. */
  public static final BigDecimal STAMP_FUTURE = new BigDecimal("0.00002000");

  /** Equity (delivery) stamp duty — 0.015% on buy-side turnover. */
  public static final BigDecimal STAMP_EQUITY = new BigDecimal("0.00001500");

  /** SEBI turnover fee — ₹10 per crore = 0.0001% on turnover. */
  public static final BigDecimal SEBI = new BigDecimal("0.00000100");

  // ---- no-spec slippage fallbacks (§D.11 per-class table) ----
  /** Equities fallback when no slippage is configured — 5 bps. */
  public static final BigDecimal EQUITY_FALLBACK_BPS = new BigDecimal("5");

  /** Futures fallback when no slippage is configured — 1 tick (liquid; tick-aligned, A9 [FP-7]). */
  public static final int FUTURE_FALLBACK_TICKS = 1;

  /** Options fallback floor when no quote is known — 1 tick (else max(1 tick, half-spread)). */
  public static final int OPTION_FALLBACK_TICKS = 1;
}
