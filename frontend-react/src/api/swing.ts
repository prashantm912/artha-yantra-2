// Swing sell-decision data layer (audit M20 + §7.2.4). The daily "would I buy it now / why am I
// holding / where am I a seller" triad for the two EOD swing books — Minervini SEPA + Manas Arora.
// Two surfaces: the LIVE recompute-on-read triad (per-family /sell-decisions), and the durable HISTORY
// the 20:05 batch persists (V037) at /signals/sell-decisions with an acknowledge affordance. Decimals
// cross the wire as JSON strings (see CLAUDE.md; render via lib/decimal).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

/** One holding's triad: the buy-now check, the hold status, and where the exits sit. */
export interface SwingSellDecision {
  signalId: number; // the held ENTRY anchor (deep-link key to the paper position)
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

/** One durable recorded sell-decision row (V037) — the acknowledgeable batch-time snapshot. */
export interface RecordedSellDecision {
  id: number;
  book: SwingFamily;
  runDate: string; // YYYY-MM-DD (IST calendar date the batch evaluated for)
  evaluatedAt: string; // ISO-8601 (offset)
  signalId: number; // deep-link key: the held ENTRY anchor
  exchange: string;
  symbol: string;
  setup: string | null;
  stage?: number | null; // Minervini only
  setupType?: string | null; // Manas only
  footprint: string | null;
  entryPrice: string | null;
  currentPrice: string | null;
  unrealizedPct: string | null;
  stopLevel: string | null;
  trailLevel: string | null;
  stillBuyable: boolean;
  sellingNow: boolean;
  sellReason: string | null;
  verdict: string;
  acknowledgedAt: string | null; // null = not yet acknowledged
}

const RECORDED_KEY = 'recorded-sell-decisions';

/** The persisted sell-decision history for one swing book, newest first (the batch writes one/day). */
export function useRecordedSellDecisions(family: SwingFamily, enabled = true) {
  return useQuery({
    queryKey: [RECORDED_KEY, family],
    queryFn: () =>
      apiFetch<{ items: RecordedSellDecision[] }>(
        `/signals/sell-decisions?book=${family}&limit=200`,
      ),
    enabled,
  });
}

/** Acknowledge one recorded decision (idempotent); refreshes the book's history on success. */
export function useAckSellDecision(family: SwingFamily) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      apiFetch<RecordedSellDecision>(`/signals/sell-decisions/${id}/ack`, {
        method: 'POST',
        json: {},
      }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: [RECORDED_KEY, family] });
    },
  });
}
