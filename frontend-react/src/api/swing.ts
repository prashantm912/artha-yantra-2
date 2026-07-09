// Swing sell-decision data layer (audit M20). The daily "would I buy it now / why am I holding /
// where am I a seller" triad for the two EOD swing books — Minervini SEPA + Manas Arora — surfaced
// from the read-only /sell-decisions endpoints (recomputed on read over each held anchor's fresh
// daily series). Decimals cross the wire as JSON strings (see CLAUDE.md; render via lib/decimal).

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** One holding's triad: the buy-now check, the hold status, and where the exits sit. */
export interface SwingSellDecision {
  symbol: string;
  setup: string | null;
  stage?: number | null; // Minervini only (Stage 1–4); absent for Manas
  setupType?: string | null; // Manas only ('vcp' | 'breakout'); absent for Minervini
  footprint: string | null;
  entryPrice: string | null;
  currentPrice: string | null;
  unrealizedPct: string | null; // already ×100 (e.g. "12.34" = +12.34%)
  stopLevel: string | null;
  trailLevel: string | null; // null until the trail arms
  stillBuyable: boolean;
  sellingNow: boolean;
  sellReason: string | null;
  verdict: string; // 'HOLD' | 'SELL (<reason>)'
}

/** The {asOf, items} envelope both swing families return. */
export interface SwingSellReport {
  asOf: string; // ISO-8601 (carries the +05:30 IST offset)
  items: SwingSellDecision[];
}

export type SwingFamily = 'minervini' | 'manas-arora';

const PATHS: Record<SwingFamily, string> = {
  minervini: '/signals/minervini-swing/sell-decisions',
  'manas-arora': '/signals/manas-arora-swing/sell-decisions',
};

/** The read-only daily sell-decision triad for one swing family's open holdings. */
export function useSwingSellDecisions(family: SwingFamily, enabled = true) {
  return useQuery({
    queryKey: ['swing-sell-decisions', family],
    queryFn: () => apiFetch<SwingSellReport>(PATHS[family]),
    enabled,
  });
}
