/**
 * Options payoff engine (greeks/payoff pipeline, first deliverable). Pure client-side math over the
 * legs a user assembles from the live chain — payoff AT EXPIRY (intrinsic value), net greeks, net
 * premium (debit/credit), and the breakeven / max-profit / max-loss derived from the curve. No new
 * data: premiums + per-share greeks come from /chain-table; lot size is supplied by the caller.
 *
 * Sign convention: BUY = +1 (pay premium, gain intrinsic), SELL = −1. Per-leg P&L at spot S =
 * side × lots × lotSize × (intrinsic(S) − premium). Greeks scale the same way (per-share → position).
 */

export type OptionType = 'CE' | 'PE';
export type Side = 'BUY' | 'SELL';

export interface PayoffLeg {
  strike: number;
  type: OptionType;
  side: Side;
  lots: number;
  /** Per-share premium (the leg's LTP from the chain). */
  premium: number;
  /** Per-share greeks (from the chain); any may be null when unpriced. */
  delta?: number | null;
  gamma?: number | null;
  theta?: number | null;
  vega?: number | null;
  rho?: number | null;
}

export interface NetGreeks {
  delta: number;
  gamma: number;
  theta: number;
  vega: number;
  rho: number;
}

export interface PayoffPoint {
  spot: number;
  pnl: number;
}

export interface PayoffResult {
  points: PayoffPoint[];
  /** Max / min P&L OVER THE EVALUATED RANGE (a long option is unbounded beyond it — see `unbounded`). */
  maxProfit: number;
  maxLoss: number;
  /** True when the payoff is still rising/falling at a range edge (true max/min is beyond the window). */
  unboundedProfit: boolean;
  unboundedLoss: boolean;
  /** Underlying levels where total P&L crosses zero (linear-interpolated), ascending. */
  breakevens: number[];
  netGreeks: NetGreeks;
  /** Net cash flow at entry: negative = net debit (paid), positive = net credit (received). */
  netPremium: number;
}

const sideSign = (side: Side): number => (side === 'BUY' ? 1 : -1);

const intrinsic = (spot: number, leg: PayoffLeg): number =>
  leg.type === 'CE' ? Math.max(spot - leg.strike, 0) : Math.max(leg.strike - spot, 0);

/** Total position P&L at expiry for a given underlying spot. */
export function pnlAtSpot(legs: PayoffLeg[], lotSize: number, spot: number): number {
  let pnl = 0;
  for (const leg of legs) {
    const qty = leg.lots * lotSize;
    pnl += sideSign(leg.side) * qty * (intrinsic(spot, leg) - leg.premium);
  }
  return pnl;
}

function netGreeks(legs: PayoffLeg[], lotSize: number): NetGreeks {
  const g: NetGreeks = { delta: 0, gamma: 0, theta: 0, vega: 0, rho: 0 };
  for (const leg of legs) {
    const w = sideSign(leg.side) * leg.lots * lotSize;
    g.delta += w * (leg.delta ?? 0);
    g.gamma += w * (leg.gamma ?? 0);
    g.theta += w * (leg.theta ?? 0);
    g.vega += w * (leg.vega ?? 0);
    g.rho += w * (leg.rho ?? 0);
  }
  return g;
}

function netPremium(legs: PayoffLeg[], lotSize: number): number {
  // BUY pays (cash out, negative), SELL receives (cash in, positive).
  let net = 0;
  for (const leg of legs) {
    net += -sideSign(leg.side) * leg.lots * lotSize * leg.premium;
  }
  return net;
}

/**
 * Builds the payoff curve over {@code [min, max]} stepped by {@code step}, plus the derived metrics.
 * Breakevens are the zero-crossings of the sampled curve (linear interpolation between samples).
 */
export function buildPayoff(
  legs: PayoffLeg[],
  lotSize: number,
  range: { min: number; max: number; step: number },
): PayoffResult {
  const points: PayoffPoint[] = [];
  const breakevens: number[] = [];
  let maxProfit = Number.NEGATIVE_INFINITY;
  let maxLoss = Number.POSITIVE_INFINITY;

  const step = range.step > 0 ? range.step : 1;
  let prev: PayoffPoint | null = null;
  for (let spot = range.min; spot <= range.max + 1e-9; spot += step) {
    const pnl = pnlAtSpot(legs, lotSize, spot);
    const point = { spot: round2(spot), pnl: round2(pnl) };
    points.push(point);
    if (pnl > maxProfit) maxProfit = pnl;
    if (pnl < maxLoss) maxLoss = pnl;
    if (prev && ((prev.pnl <= 0 && pnl >= 0) || (prev.pnl >= 0 && pnl <= 0)) && prev.pnl !== pnl) {
      // linear interpolation of the zero crossing between prev and this sample
      const t = -prev.pnl / (pnl - prev.pnl);
      breakevens.push(round2(prev.spot + t * (point.spot - prev.spot)));
    }
    prev = point;
  }

  const n = points.length;
  const unboundedProfit = n >= 2 && points[n - 1].pnl > points[n - 2].pnl && points[n - 1].pnl >= maxProfit - 1e-6;
  const unboundedLoss = n >= 2 && points[0].pnl < points[1].pnl && points[0].pnl <= maxLoss + 1e-6;

  return {
    points,
    maxProfit: legs.length ? round2(maxProfit) : 0,
    maxLoss: legs.length ? round2(maxLoss) : 0,
    unboundedProfit,
    unboundedLoss,
    breakevens: dedupe(breakevens),
    netGreeks: netGreeks(legs, lotSize),
    netPremium: round2(netPremium(legs, lotSize)),
  };
}

function round2(v: number): number {
  return Math.round(v * 100) / 100;
}

/** Drop near-duplicate breakevens (a flat-at-zero segment can emit several). */
function dedupe(xs: number[]): number[] {
  const out: number[] = [];
  for (const x of [...xs].sort((a, b) => a - b)) {
    if (!out.length || Math.abs(out[out.length - 1] - x) > 0.5) out.push(x);
  }
  return out;
}
