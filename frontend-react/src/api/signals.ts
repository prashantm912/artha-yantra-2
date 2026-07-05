// Signals cockpit data layer (master plan §20 cockpit-parity / C-2.22-2.26). Ports Angular's
// SignalsStore: REST history page + live unshift from the STOMP `/topic/signals` channel into a
// BOUNDED ring, deduped by id; on WS reconnect the snapshot re-fetches (gap-heal). Take/Dismiss are
// the first mutations in the React app — apiFetch echoes XSRF on the POST automatically.

import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import { wsClient } from '../lib/wsClient.ts';

/** One signal as both the REST snapshot and the live channel deliver it (frozen C-2.6 shape). */
export interface SignalDto {
  id: number;
  strategyVersionId?: string;
  strategyName?: string;
  strategyId?: string;
  version?: string;
  checksum?: string;
  exchange: string;
  tradingsymbol: string;
  interval: string;
  signalType: 'ENTRY' | 'EXIT';
  side: 'BUY' | 'SELL';
  entryPrice: string | null;
  stopLoss: string | null;
  target: string | null;
  compositeScore: string;
  scoreBreakdown: ScoreBreakdownDto;
  status: 'ACTIVE' | 'EXPIRED' | 'TAKEN' | 'DISMISSED';
  generatedAt: string;
  expiresAt?: string | null;
  suggestedQty?: string | null;
  /** The directly-tradeable option leg (scalper signals only; null otherwise). */
  tradeableExchange?: string | null;
  tradeableTradingsymbol?: string | null;
  /** Scalper enrichment (option leg + confluence dots + manual-verify checklist); null for non-scalper signals. */
  scalperDetail?: ScalperDetail | null;
}

/** One automated confluence dot (read-only): green supports the side, red opposes it. */
export interface ConfluenceDot {
  dot: string;
  weight: number;
  supports: boolean;
}

/** One owner manual-verification check (rendered dynamically — do NOT hardcode the set). */
export interface ManualCheck {
  key: string;
  label: string;
  doc_ref: string;
  assist: string;
}

/** Scalper signal enrichment carried by the signal DTO (frozen C-2.6 scalper extension). */
export interface ScalperDetail {
  side: 'CE' | 'PE';
  underlying: string;
  expiry: string;
  tradeable: string;
  strike: number;
  option_ltp: number;
  iv: number;
  delta: number;
  confluence_aggregate: number;
  dots: ConfluenceDot[];
  manual_checks: ManualCheck[];
  /** W4 6c OIP-AI Open=High probability — open-high-low strategies only (absent otherwise). */
  oh_tier?: string | null;
  oh_prob_pct?: number | null;
  badge?: boolean | null;
}

/** The frozen C-2.6 reasoning contract (composite = Σ contributions ÷ weightDenominator). */
export interface ScoreBreakdownDto {
  composite: number | string;
  threshold: number | string;
  passed: boolean;
  requiredComposite: number | string;
  optionalMinScore: number | string;
  optionalGateMargin: number | string;
  weightDenominator: number | string;
  gate: GateNodeDto;
  indicators: IndicatorEntryDto[];
}

/** Recursive gate-tree node. */
export interface GateNodeDto {
  kind: string;
  rule?: string;
  passed: boolean;
  operands?: Record<string, number | string | null>;
  children?: GateNodeDto[];
}

/** One scoring-indicator line. */
export interface IndicatorEntryDto {
  alias: string;
  name: string;
  timeframe: string;
  score: number | string | null;
  weight: number | string;
  contribution: number | string;
  optional: boolean;
  activated: boolean;
  activationReason: 'REQUIRED' | 'ACTIVATED' | 'SCORE_BELOW_MIN' | 'MARGIN_NOT_MET';
  rawValue: number | string | null;
  params: Record<string, unknown>;
}

interface SignalPage {
  items: SignalDto[];
}

/** The live feed keeps at most this many rows in memory (bounded ring, C-2.22). */
export const SIGNAL_RING_LIMIT = 200;

export const SIGNAL_STATUSES = ['ACTIVE', 'EXPIRED', 'TAKEN', 'DISMISSED'] as const;

const SIGNALS_KEY = 'signals';

/**
 * REST history page; the live channel and reconnect heal it. `status=null` = all. `from`/`to` are
 * ISO datetimes bounding `generated_at` (the Live=today vs Historical=picked-day filter) — null = no
 * bound. They join the query key so each day is cached separately. `offset` pages past the 200-row
 * window (audit §6 — the cap was silent); the live merge only touches page 0. `book` filters to a
 * strategy family (`scalper` / `minervini` / `manas-arora`); null → all books.
 */
export function useSignals(
  status: string | null,
  from: string | null = null,
  to: string | null = null,
  offset: number = 0,
  book: string | null = null,
) {
  return useQuery({
    queryKey: [SIGNALS_KEY, status, from, to, offset, book],
    queryFn: () => {
      const params = new URLSearchParams({ limit: '200', offset: String(offset) });
      if (status) params.set('status', status);
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      if (book) params.set('book', book);
      return apiFetch<SignalPage>(`/signals?${params.toString()}`);
    },
  });
}

/**
 * Hydrates a single signal via GET `/signals/{id}` when its detail view opens. The live STOMP frame
 * MAY omit `scalperDetail`, so the detail panel/ticket reads from THIS (not the list row) to render the
 * scalper enrichment + manual-verify checklist. `null` id disables the fetch.
 */
export function useSignalDetail(id: number | null) {
  return useQuery({
    queryKey: [SIGNALS_KEY, 'detail', id],
    enabled: id != null,
    queryFn: () => apiFetch<SignalDto>(`/signals/${id}`),
  });
}

/**
 * Subscribes the active query to the `/topic/signals` channel: each frame is merged into the cache
 * (newest first, deduped by id, ring-bounded). A frame whose status no longer matches the active
 * filter only updates a row already shown (status transition); a reconnect re-fetches the snapshot.
 */
export function useSignalsLive(
  status: string | null,
  from: string | null = null,
  to: string | null = null,
  live: boolean = true,
  book: string | null = null,
) {
  const qc = useQueryClient();
  useEffect(() => {
    // Only the LIVE (today) view merges the STOMP feed — a Historical day is a static past snapshot,
    // so an incoming live signal must not pollute it.
    if (!live) {
      return;
    }
    const merge = (body: string) => {
      let sig: SignalDto;
      try {
        sig = JSON.parse(body) as SignalDto;
      } catch {
        return; // unparseable frame — the REST snapshot heals
      }
      qc.setQueryData<SignalPage>([SIGNALS_KEY, status, from, to, 0, book], (prev) => {
        const items = prev?.items ?? [];
        if (status && sig.status !== status) {
          const next = items.map((i) => (i.id === sig.id ? sig : i));
          return { items: next };
        }
        const without = items.filter((i) => i.id !== sig.id);
        return { items: [sig, ...without].slice(0, SIGNAL_RING_LIMIT) };
      });
    };
    const offTopic = wsClient.topic('/topic/signals', merge);
    // reconnect gap-heal rides the app-wide debounced invalidation in main.tsx — a per-hook
    // invalidation here was a redundant second refetch of the same key
    return () => {
      offTopic();
    };
  }, [qc, status, from, to, live, book]);
}

/** Replaces a row across every cached signals page (after a take/dismiss round-trip). */
function replaceInCaches(qc: QueryClient, updated: SignalDto): void {
  qc.setQueriesData<SignalPage>({ queryKey: [SIGNALS_KEY] }, (prev) =>
    prev ? { items: prev.items.map((i) => (i.id === updated.id ? updated : i)) } : prev,
  );
}

/** Mark a signal taken; a qty opens a paper position (A12 prefill). */
export function useTakeSignal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, qty }: { id: number; qty?: number }) =>
      apiFetch<SignalDto>(`/signals/${id}/taken`, { method: 'POST', json: qty ? { qty } : {} }),
    onSuccess: (updated) => replaceInCaches(qc, updated),
  });
}

/** Dismiss (reject) a signal. */
export function useDismissSignal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      apiFetch<SignalDto>(`/signals/${id}/dismiss`, { method: 'POST', json: {} }),
    onSuccess: (updated) => replaceInCaches(qc, updated),
  });
}
