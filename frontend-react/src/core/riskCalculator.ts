// Risk Calculator (oipulse "Risk Calculator") — a pure, client-side position-sizing utility. Given an
// account, a per-trade risk budget, an entry and a protective stop, it derives the position size that
// caps the loss at the risk budget, plus the reward-target and the Siva "1% of capital" lock price.
// No backend: every value is computed here so the page is a thin presentation layer over this module.

export interface RiskInput {
  /** Account capital (INR) the risk budget is a fraction of. */
  capital: number;
  /** Percent of capital risked on this single trade (the loss if the stop is hit). */
  riskPct: number;
  /** Planned entry price. */
  entry: number;
  /** Protective stop-loss price (below entry = LONG, above = SHORT). */
  stop: number;
  /** Contract/lot size — 1 for cash, the F&O lot for options/futures. */
  lotSize: number;
  /** Target reward-to-risk multiple (e.g. 2 = a 2R target). */
  rewardRisk: number;
}

export interface RiskResult {
  side: 'LONG' | 'SHORT' | null;
  /** INR at risk if the stop is hit = capital × riskPct/100. */
  riskAmount: number;
  /** Per-unit risk = |entry − stop|. */
  perUnitRisk: number;
  /** Unrounded position size = riskAmount / perUnitRisk. */
  rawUnits: number;
  /** Whole lots = floor(rawUnits / lotSize). */
  lots: number;
  /** Actual sized units = lots × lotSize (≤ rawUnits, so the realised risk never exceeds the budget). */
  units: number;
  /** Capital deployed at entry = units × entry. */
  capitalDeployed: number;
  /** Deployed as a percent of capital. */
  deploymentPct: number;
  /** Reward-target price = entry ± perUnitRisk × rewardRisk (sign by side). */
  target: number;
  /** Profit (INR) at the reward-target on the sized units. */
  rewardAmount: number;
  /** Realised risk (INR) at the stop on the sized units (≤ riskAmount after lot rounding). */
  sizedRisk: number;
  /** The price at which the sized position has gained 1% of capital (Siva's 1% profit-lock). */
  onePctTarget: number;
  /** True when the inputs yield a sizeable, non-degenerate position. */
  valid: boolean;
}

const EMPTY: RiskResult = {
  side: null,
  riskAmount: 0,
  perUnitRisk: 0,
  rawUnits: 0,
  lots: 0,
  units: 0,
  capitalDeployed: 0,
  deploymentPct: 0,
  target: 0,
  rewardAmount: 0,
  sizedRisk: 0,
  onePctTarget: 0,
  valid: false,
};

/** Computes the position-sizing result. Degenerate inputs (≤0 capital/entry, entry==stop) → invalid. */
export function computeRisk(input: RiskInput): RiskResult {
  const { capital, riskPct, entry, stop, rewardRisk } = input;
  const lotSize = Math.max(1, Math.floor(input.lotSize || 1));
  const perUnitRisk = Math.abs(entry - stop);

  if (capital <= 0 || entry <= 0 || riskPct <= 0 || perUnitRisk === 0) {
    return EMPTY;
  }

  const side: 'LONG' | 'SHORT' = stop < entry ? 'LONG' : 'SHORT';
  const dir = side === 'LONG' ? 1 : -1;
  const riskAmount = (capital * riskPct) / 100;
  const rawUnits = riskAmount / perUnitRisk;
  const lots = Math.floor(rawUnits / lotSize);
  const units = lots * lotSize;
  const capitalDeployed = units * entry;
  const target = entry + dir * perUnitRisk * Math.max(0, rewardRisk);
  const rewardAmount = units * perUnitRisk * Math.max(0, rewardRisk);
  const sizedRisk = units * perUnitRisk;
  const onePctTarget = units > 0 ? entry + (dir * (capital * 0.01)) / units : entry;

  return {
    side,
    riskAmount,
    perUnitRisk,
    rawUnits,
    lots,
    units,
    capitalDeployed,
    deploymentPct: capital > 0 ? (capitalDeployed / capital) * 100 : 0,
    target,
    rewardAmount,
    sizedRisk,
    onePctTarget,
    valid: units > 0,
  };
}
