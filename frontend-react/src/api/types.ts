import type { OiInterpretation } from '../core/oiInterpretation.ts';

// OI wire types (ported from frontend-ui oi-analytics.store.ts). BigDecimal fields are JSON STRINGS
// at runtime (Jackson) even though the generated .d.ts types them as `number` — keep them `string`
// and never parseFloat. `long` OI/count fields are numbers. Anchor-scoped subset; more added per wave.

/** GET /api/v1/market/options/oi-stats — bare object; 422 DATA_GAP when no snapshot. */
export interface OiStats {
  pcr: string | null;
  maxPain: string | null;
  ceOi: number;
  peOi: number;
  asOf: string;
}

/** One active strike from GET /api/v1/market/options/active-strikes. */
export interface StrikeView {
  strike: string;
  ceOi: number;
  peOi: number;
}

/** GET /api/v1/market/options/active-strikes — sentiment + top-N strikes; 422 DATA_GAP on empty. */
export interface ActiveStrikes {
  sentimentPct: string | null;
  items: StrikeView[];
  asOf: string;
}

/** One row of GET /api/v1/market/options/oi-analysis `{items}` (per bucket·strike·optionType). */
export interface OiStrikePoint {
  bucket: string;
  strike: string;
  optionType: 'CE' | 'PE';
  ltp: string | null;
  oi: number | null;
  oiChange: number | null;
  iv: string | null;
  spot: string | null;
}

/** GET /api/v1/market/options/oi-analysis/strike-series — one strike's CE+PE points per session bucket. */
export interface StrikeSeries {
  underlying: string;
  expiry: string;
  strike: string;
  interval: string;
  asOf: string;
  items: OiStrikePoint[];
}

/** One row of GET /api/v1/market/options/spurt `{items}` — per strike·side interval buildup. */
export interface SpurtRow {
  strike: string;
  optionType: 'CE' | 'PE';
  ltp: string | null;
  prevLtp: string | null;
  oi: number | null;
  oiChange: number;
  spurtPct: string | null;
  ltpChange: string | null;
  ltpChangePct: string | null;
  volume: number | null;
  interpretation: OiInterpretation;
}

/** GET /api/v1/market/options/spurt summary: spot-dir × total-OI-dir → the 4-state badge. */
export interface SpurtSummary {
  interpretation: OiInterpretation;
  spotDelta: string;
  oiChange: number;
}

/** GET /api/v1/market/options/spurt — per-strike buildup + the underlying rollup; 422 on no snapshot. */
export interface SpurtChain {
  items: SpurtRow[];
  summary: SpurtSummary | null;
  asOf: string | null;
}

/** A CE/PE leg's cell values in the folded strike grid. */
export interface LegCell {
  oi: number | null;
  oiChange: number | null;
  iv: string | null;
  ltp: string | null;
}

/** One folded chain row: CE + PE for a strike (oipulse mirrored grid). */
export interface OiChainRow {
  strike: string;
  ce: LegCell | null;
  pe: LegCell | null;
  spot: string | null;
}

// ── /chain-table — the faithful Options Chain feed (§20.7): live black76 greeks + interval deltas.

/** Interval deltas overlaid on a live leg (null when no prior snapshot bucket for this strike·side). */
export interface LegDeltas {
  oiChange: number | null;
  oiChangePct: string | null;
  ltpChange: string | null;
  ltpChangePct: string | null;
  interpretation: OiInterpretation;
}

/** A full live chain leg — IV + all 5 greeks computed server-side in black76 (decimal strings). */
export interface ChainLeg {
  tradingsymbol: string;
  ltp: string | null;
  bid: string | null;
  ask: string | null;
  volume: number | null;
  oi: number | null;
  iv: string | null;
  delta: string | null;
  gamma: string | null;
  theta: string | null;
  vega: string | null;
  rho: string | null;
  ivReason: string;
  priceSource: string | null;
}

/** A live leg plus its interval deltas (deltas null until a snapshot pair has accrued). */
export interface ChainTableLeg {
  leg: ChainLeg;
  deltas: LegDeltas | null;
}

/** One faithful-chain row: CE | strike | PE, each leg enriched with deltas. */
export interface ChainTableRow {
  strike: string;
  ce: ChainTableLeg | null;
  pe: ChainTableLeg | null;
}

/** GET /api/v1/market/options/chain-table — live chain header + enriched rows + the delta interval. */
export interface ChainTable {
  underlying: string;
  expiry: string;
  spot: string | null;
  forward: string | null;
  forwardSource: string | null;
  riskFreeRate: string | null;
  pcr: string | null;
  stale: boolean;
  asOf: string;
  interval: string;
  rows: ChainTableRow[];
}
