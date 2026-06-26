// The shared field glossary (#16 — universal labels + tooltips). One canonical, plain-language
// explanation per recurring data field, authored once here and referenced from every page so the
// same column means the same thing app-wide (an "OI" tooltip reads identically on the chain, the
// heatmap, and the spurt scanner). Pages pass `help={FIELD_HELP.oi}` to a DataColumn or compose a
// bespoke string for a page-unique field. Keep each entry ONE trader-readable sentence, no jargon
// the tooltip itself doesn't unpack. Grouped by domain for editing; the export is a flat lookup.

export const FIELD_HELP = {
  // ── Price / quote ────────────────────────────────────────────────────────────────────────────
  ltp: 'Last traded price — the most recent price the instrument changed hands at.',
  open: 'Opening price of the session.',
  high: 'Highest price reached so far in the session.',
  low: 'Lowest price reached so far in the session.',
  close: 'Closing price of the session (the last print of the day once the market shuts).',
  prevClose: "The previous session's closing price — the baseline for today's change.",
  netChange: "Absolute price change versus the previous close (positive = up, negative = down).",
  changePct: 'Percentage price change versus the previous close.',
  volume: 'Number of contracts/shares traded — a measure of activity and liquidity.',
  turnover: 'Traded value (price × quantity) over the period.',
  vwap: 'Volume-weighted average price — the average traded price weighted by volume, a fair-value gauge.',
  spot: 'The underlying index/stock cash price the option or future is referenced to.',
  bid: 'Best buy price currently on the order book.',
  ask: 'Best sell price currently on the order book.',

  // ── Open interest ────────────────────────────────────────────────────────────────────────────
  oi: 'Open Interest — the total number of outstanding (not yet squared-off) contracts. Rising OI means new positions are being added.',
  oiChange: 'Change in Open Interest versus the previous reading — new positions added (+) or closed (−).',
  oiChangePct: 'Percentage change in Open Interest versus the baseline reading.',
  ceOi: 'Call-side Open Interest at this strike — heavy call OI often marks resistance.',
  peOi: 'Put-side Open Interest at this strike — heavy put OI often marks support.',
  oiInterpretation:
    'Price-vs-OI read: Long Buildup (price↑ OI↑), Short Buildup (price↓ OI↑), Short Covering (price↑ OI↓), Long Unwinding (price↓ OI↓).',
  pcr: 'Put-Call Ratio — put OI ÷ call OI. Above 1 leans bearish positioning (more puts); below 1 leans bullish.',
  maxPain: 'The strike at which option buyers lose the most — price often gravitates here near expiry.',

  // ── Options structure ────────────────────────────────────────────────────────────────────────
  strike: 'The exercise price of the option contract.',
  expiry: 'The contract expiry date — when the option/future settles.',
  premium: 'The option price (LTP of the option contract itself), what the buyer pays per unit.',
  moneyness: 'Where the strike sits versus spot: ITM (in-the-money), ATM (at-the-money), OTM (out-of-the-money).',
  atm: 'At-the-money — the strike closest to the current spot price.',

  // ── Greeks / volatility ──────────────────────────────────────────────────────────────────────
  iv: 'Implied Volatility — the market’s expected volatility priced into the option; higher IV = pricier options.',
  delta: 'Delta — how much the option price moves per 1-point move in the underlying (and a rough probability of finishing ITM).',
  gamma: 'Gamma — the rate of change of delta; high gamma means delta shifts fast near the strike.',
  theta: 'Theta — daily time decay; how much premium the option loses per day, all else equal.',
  vega: 'Vega — sensitivity of the option price to a 1-point change in implied volatility.',
  rho: 'Rho — sensitivity of the option price to a change in interest rates (small for short-dated options).',
  vix: 'India VIX — the market’s expected 30-day volatility of the NIFTY; a fear/complacency gauge.',

  // ── Breadth / market ─────────────────────────────────────────────────────────────────────────
  advance: 'Count of constituents trading up versus the previous close.',
  decline: 'Count of constituents trading down versus the previous close.',
  deliveryPct: 'Share of traded volume taken to delivery (not intraday-squared) — higher = more conviction.',

  // ── Backtest / strategy metrics ──────────────────────────────────────────────────────────────
  totalReturn: 'Total return of the run over the test window, net of costs.',
  cagr: 'Compound Annual Growth Rate — the annualised return of the equity curve.',
  sharpe: 'Sharpe ratio — risk-adjusted return (excess return ÷ volatility); higher is better.',
  maxDrawdown: 'The largest peak-to-trough equity decline during the run — a worst-case pain measure.',
  winRate: 'Share of trades that closed profitable.',
  profitFactor: 'Gross profit ÷ gross loss; above 1 means winners outweigh losers.',
  tradeCount: 'Number of trades the run executed.',
  oosFoldMean: 'Out-of-sample fold mean — the average objective across walk-forward test folds (the un-overfit read).',
} as const;

export type FieldHelpKey = keyof typeof FIELD_HELP;

/** Safe lookup for a glossary key (returns undefined for an unknown key). */
export function fieldHelp(key: FieldHelpKey): string {
  return FIELD_HELP[key];
}
