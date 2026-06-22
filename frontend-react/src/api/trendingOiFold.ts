import type { SentimentTone } from '../components/atoms/SentimentBadge.tsx';
import { addDecimal, subtractDecimal } from '../lib/decimal.ts';
import type { TrendPoint } from './types.ts';

// Trending OI fold (oipulse §options/trending-oi): the table's derived columns are ALL computed
// client-side from the per-bucket CE/PE OI series (the BE /trending feed), exactly as oipulse does it.
//
// Faithful definitions from the study doc (confirmed live 2026-06-18):
//   Chng. In Call/Put OI = cumulative Δ vs the baseline row (oipulse uses the prior-day EOD "PEOD"
//     baseline; we have intraday buckets only, so the baseline is the FIRST captured bucket of the
//     session — a documented reduced-history divergence, not a formula change).
//   Diff. in OI       = ΔPut OI − ΔCall OI (puts minus calls); POSITIVE = Bullish.
//   Net PCR           = totalPeOi / totalCeOi.
//   Day H/L Break     = Diff-in-OI prints a NEW day extreme AND exceeds the previous bucket's value
//                       (positive → High Break, negative → Low Break); seeds after the baseline.
//   Sentiment (5-level): sign of Diff sets Bullish/Bearish/Neutral; a Day-Break with the OPPOSITE
//                       side unwinding (calls falling on a bull / puts falling on a bear) escalates to
//                       Extreme — the doc's strength ladder.
//
// The two oipulse columns whose exact formula was not captured — "Chng. In Direction" and "Direction
// of chng. %" — use a documented derivation: ΔDiff vs the prior bucket, and Diff as a % of the total
// directional OI change (|ΔCall| + |ΔPut|). See docs/manual-tests/phase-4-wave2-*.md.

export interface SentimentLevel {
  label: 'Ext. Bullish' | 'Bullish' | 'Neutral' | 'Bearish' | 'Ext. Bearish';
  tone: SentimentTone;
}

export interface TrendingRow {
  bucket: string;
  spot: string | null;
  chngCallOi: number;
  chngPutOi: number;
  diffInOi: number;
  /** sign of Diff-in-OI: +1 / -1 / 0. */
  direction: number;
  /** Δ(Diff-in-OI) vs the prior bucket (derived — see file header). */
  chngInDirection: number;
  /** Diff-in-OI as a % of total directional OI change, signed (derived); null when no change yet. */
  directionPct: string | null;
  netPcr: string | null;
  dhBreak: boolean;
  dlBreak: boolean;
  /** underlying level at the break (oipulse shows the price beside D.H.B / D.L.B). */
  breakLevel: string | null;
  sentiment: SentimentLevel;
  // ── Trending OI - PA price-action columns (summed CE/PE premium + deltas vs the session-open baseline).
  /** Total Call Ltp — summed CE premium across the chain (decimal string). */
  totalCallLtp: string | null;
  /** Total Put Ltp — summed PE premium. */
  totalPutLtp: string | null;
  /** Δ total CE premium vs the baseline bucket. */
  callLtpChng: string | null;
  /** Δ total PE premium vs the baseline bucket. */
  putLtpChng: string | null;
  /** Δ (CE+PE) premium = the straddle premium change (callLtpChng + putLtpChng) — the key PA signal. */
  straddleChng: string | null;
}

function sentiment(diff: number, dhBreak: boolean, dlBreak: boolean, chngCallOi: number, chngPutOi: number): SentimentLevel {
  if (diff === 0) return { label: 'Neutral', tone: 'neutral' };
  if (diff > 0) {
    // Bullish (puts dominant). Extreme when a High-Break coincides with call unwinding (opposite side).
    return dhBreak && chngCallOi < 0
      ? { label: 'Ext. Bullish', tone: 'bull' }
      : { label: 'Bullish', tone: 'bull' };
  }
  return dlBreak && chngPutOi < 0
    ? { label: 'Ext. Bearish', tone: 'bear' }
    : { label: 'Bearish', tone: 'bear' };
}

/** Folds the per-bucket OI series (oldest-first, the BE contract) into the table rows. */
export function foldTrending(items: TrendPoint[]): TrendingRow[] {
  if (items.length === 0) return [];
  const baseCe = items[0].ceOi;
  const basePe = items[0].peOi;
  const baseCeLtp = items[0].ceLtp;
  const basePeLtp = items[0].peLtp;

  let runMax = Number.NEGATIVE_INFINITY;
  let runMin = Number.POSITIVE_INFINITY;
  let prevDiff = 0;

  return items.map((p, i) => {
    const chngCallOi = p.ceOi - baseCe;
    const chngPutOi = p.peOi - basePe;
    const diffInOi = chngPutOi - chngCallOi;

    const newMax = diffInOi > runMax;
    const newMin = diffInOi < runMin;
    runMax = Math.max(runMax, diffInOi);
    runMin = Math.min(runMin, diffInOi);

    // Break only after the baseline bucket (the baseline diff is 0 and seeds the running extremes).
    const dhBreak = i > 0 && newMax && diffInOi > prevDiff && diffInOi > 0;
    const dlBreak = i > 0 && newMin && diffInOi < prevDiff && diffInOi < 0;

    const totalDirectional = Math.abs(chngCallOi) + Math.abs(chngPutOi);
    const directionPct = totalDirectional === 0 ? null : ((diffInOi / totalDirectional) * 100).toFixed(2);
    const netPcr = p.ceOi === 0 ? null : (p.peOi / p.ceOi).toFixed(2);

    // PA premium columns: Δ vs the session-open baseline (same baseline convention as the OI columns).
    const callLtpChng =
      p.ceLtp != null && baseCeLtp != null ? subtractDecimal(p.ceLtp, baseCeLtp) : null;
    const putLtpChng =
      p.peLtp != null && basePeLtp != null ? subtractDecimal(p.peLtp, basePeLtp) : null;
    const straddleChng =
      callLtpChng != null && putLtpChng != null ? addDecimal(callLtpChng, putLtpChng) : null;

    const row: TrendingRow = {
      bucket: p.bucket,
      spot: p.spot,
      chngCallOi,
      chngPutOi,
      diffInOi,
      direction: Math.sign(diffInOi),
      chngInDirection: i === 0 ? 0 : diffInOi - prevDiff,
      directionPct,
      netPcr,
      dhBreak,
      dlBreak,
      breakLevel: dhBreak || dlBreak ? p.spot : null,
      sentiment: sentiment(diffInOi, dhBreak, dlBreak, chngCallOi, chngPutOi),
      totalCallLtp: p.ceLtp,
      totalPutLtp: p.peLtp,
      callLtpChng,
      putLtpChng,
      straddleChng,
    };
    prevDiff = diffInOi;
    return row;
  });
}
