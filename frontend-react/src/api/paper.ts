// Paper-trading cockpit data layer (master plan §20 parity / F-43). Ports Angular's PaperStore:
// open positions with server-computed mark-to-market, the closed-trade ledger, the realized-equity
// curve, the account header and the global risk limits (kill switch / max-open / daily-loss). The MTM
// stays fresh via a short refetchInterval (the BE computes markPrice/unrealizedPnl server-side); the
// WS per-symbol tick overlay the Angular page layered on top is a later enhancement.
//
// Per-book split (owner request): the single paper book is now three per-family books — `scalper`,
// `minervini`, `manas-arora` — each with its own capital/risk/positions. Every hook takes an optional
// `book`: passed → `?book=` on the read + `book` in the query key (so switching books refetches);
// omitted → today's all-books aggregate. The capital + risk mutations REQUIRE a book (the BE 400s a
// blank one); reset accepts an optional book (omitted → all books).

import { useEffect } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';
import { wsClient } from '../lib/wsClient.ts';

/** A per-family paper book — its own capital, risk config, positions and signals. */
export type PaperBook = 'scalper' | 'minervini' | 'manas-arora';

/** The paper books in menu order, with their human labels. */
export const PAPER_BOOKS: { book: PaperBook; label: string }[] = [
  { book: 'scalper', label: 'Scalper' },
  { book: 'minervini', label: 'Minervini' },
  { book: 'manas-arora', label: 'Manas Arora' },
];

/** The human label for a book slug (falls back to the raw slug for an unknown book). */
export function bookLabel(book: string | undefined): string | undefined {
  if (!book) return undefined;
  return PAPER_BOOKS.find((b) => b.book === book)?.label ?? book;
}

/** An open paper position with its server-computed mark-to-market. */
export interface PaperPosition {
  id: number;
  exchange: string;
  tradingsymbol: string;
  side: 'BUY' | 'SELL';
  qty: number;
  avgEntryPrice: string;
  markPrice: string | null;
  unrealizedPnl: string | null;
  realizedPnl: string;
  status: string;
  openedAt: string;
  stopLoss: string | null;
  takeProfit: string | null;
}

/** A closed trade in the ledger. */
export interface PaperTrade {
  id: number;
  exchange: string;
  tradingsymbol: string;
  side: 'BUY' | 'SELL';
  qty: number;
  avgEntryPrice: string;
  realizedPnl: string;
  openedAt: string;
  closedAt: string;
}

/** Daily realized-equity point + the aggregate summary. */
export interface PaperPnl {
  points: { date: string; equity: string }[];
  summary: {
    realizedTotal: string;
    trades: number;
    winRate: string | null;
    expectancy: string | null;
  };
}

/** The paper-account header (A12): equity, free cash, capital usage, day P&L. */
export interface PaperAccount {
  startingCapital: string;
  cash: string;
  equity: string;
  realized: string;
  unrealized: string;
  dayPnl: string;
  openPositions: number;
  capitalUsed: string;
  usageByClass: Record<string, string>;
  marginPercents: Record<string, string>;
}

/** One global risk-limit row. */
export interface RiskSetting {
  key: string;
  value: Record<string, unknown>;
  updatedAt: string;
}

const PAPER = 'paper';
const RISK = 'risk';
const MTM_REFETCH_MS = 5000;

/** Appends `?book=` when a book is set (omitted → the all-books aggregate). */
function bookQuery(book: string | undefined, extra = ''): string {
  const params = new URLSearchParams(extra);
  if (book) params.set('book', book);
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

export function usePaperPositions(book?: string) {
  return useQuery({
    queryKey: [PAPER, 'positions', book ?? null],
    queryFn: () => apiFetch<{ items: PaperPosition[] }>(`/paper/positions${bookQuery(book)}`),
    refetchInterval: MTM_REFETCH_MS,
  });
}

export function usePaperTrades(book?: string) {
  return useQuery({
    queryKey: [PAPER, 'trades', book ?? null],
    queryFn: () => apiFetch<{ items: PaperTrade[] }>(`/paper/trades${bookQuery(book, 'limit=200')}`),
  });
}

export function usePaperPnl(book?: string) {
  return useQuery({
    queryKey: [PAPER, 'pnl', book ?? null],
    queryFn: () => apiFetch<PaperPnl>(`/paper/pnl${bookQuery(book)}`),
  });
}

export function usePaperAccount(book?: string) {
  return useQuery({
    queryKey: [PAPER, 'account', book ?? null],
    queryFn: () => apiFetch<PaperAccount>(`/paper/account${bookQuery(book)}`),
    refetchInterval: MTM_REFETCH_MS,
  });
}

export function useRiskSettings(book?: string) {
  return useQuery({
    queryKey: [RISK, 'settings', book ?? null],
    queryFn: () => apiFetch<{ items: RiskSetting[] }>(`/risk/settings${bookQuery(book)}`),
  });
}

/** Invalidates every paper query (after a mutation that changes the ledger). */
function usePaperInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: [PAPER] });
  };
}

/** Edit a book's starting capital — the BE requires the book (a blank one 400s). */
export function useUpdateCapital(book?: string) {
  const invalidate = usePaperInvalidate();
  return useMutation({
    mutationFn: (startingCapital: string) =>
      apiFetch<PaperAccount>('/paper/account', { method: 'PUT', json: { startingCapital, book } }),
    onSuccess: invalidate,
  });
}

/** Place a paper order — from a signal (signalId) or manual (exchange/tradingsymbol/side). A null
 * price simulates a market fill. Used by the scalper cockpit's order ticket (Phase 4b). */
export interface PaperOrderRequest {
  signalId?: number;
  exchange?: string;
  tradingsymbol?: string;
  side?: 'BUY' | 'SELL';
  qty: number;
  price?: string;
  stopLoss?: string;
  takeProfit?: string;
  book?: string;
}

export function usePlacePaperOrder() {
  const invalidate = usePaperInvalidate();
  return useMutation({
    mutationFn: (body: PaperOrderRequest) =>
      apiFetch<PaperPosition>('/paper/orders', { method: 'POST', json: body }),
    onSuccess: invalidate,
  });
}

export function useClosePosition() {
  const invalidate = usePaperInvalidate();
  return useMutation({
    mutationFn: ({ id, price }: { id: number; price?: string }) =>
      apiFetch<PaperTrade>(`/paper/positions/${id}/close`, {
        method: 'POST',
        json: price ? { price } : {},
      }),
    onSuccess: invalidate,
  });
}

/** Wipe a book's ledger (book omitted → all books). */
export function useResetLedger(book?: string) {
  const invalidate = usePaperInvalidate();
  return useMutation({
    mutationFn: () =>
      apiFetch<void>('/paper/reset', { method: 'POST', json: { confirm: true, book } }),
    onSuccess: invalidate,
  });
}

/** Upsert one of a book's risk limits — the BE requires the book (a blank one 400s). */
export function useUpdateRisk(book?: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: Record<string, unknown> }) =>
      apiFetch<{ items: RiskSetting[] }>('/risk/settings', {
        method: 'PUT',
        json: { book, key, value },
      }),
    onSuccess: (res) => qc.setQueryData([RISK, 'settings', book ?? null], res),
  });
}

/** True when a named risk limit is enabled. */
export function riskEnabled(items: RiskSetting[] | undefined, key: string): boolean {
  return items?.find((r) => r.key === key)?.value?.['enabled'] === true;
}

// Paper-position lifecycle events (app-platform audit §7.2.2). An append-only stream of what happened
// to a position — OPENED / CLOSED / BRACKET_HIT / SETTLED — served by GET /paper/events and pushed live
// on /topic/paper.events. Kept GENERIC on purpose: the trade-chain + position-detail builders (a later
// slice) consume these hooks with a positionId; a book/day view uses the other filters.

/** One paper-position lifecycle event. `reason`/`realizedPnl` are null on an OPENED event. */
export interface PaperEvent {
  id: number;
  positionId: number;
  kind: 'OPENED' | 'CLOSED' | 'BRACKET_HIT' | 'SETTLED';
  book: string;
  exchange: string;
  tradingsymbol: string;
  side: 'BUY' | 'SELL';
  qty: number;
  reason: string | null;
  realizedPnl: string | null;
  createdAt: string;
}

/** Event-read filters (all optional, ANDed server-side). `day` is an IST calendar date (YYYY-MM-DD). */
export interface PaperEventsFilter {
  positionId?: number;
  book?: string;
  day?: string;
}

const PAPER_EVENTS = 'paper-events';

function paperEventsKey(f: PaperEventsFilter) {
  return [PAPER_EVENTS, f.positionId ?? null, f.book ?? null, f.day ?? null] as const;
}

/** REST page of lifecycle events for a filter (position-detail / trade-chain / day view). */
export function usePaperEvents(filter: PaperEventsFilter = {}) {
  return useQuery({
    queryKey: paperEventsKey(filter),
    queryFn: () => {
      const params = new URLSearchParams({ limit: '200' });
      if (filter.positionId != null) params.set('positionId', String(filter.positionId));
      if (filter.book) params.set('book', filter.book);
      if (filter.day) params.set('day', filter.day);
      return apiFetch<{ items: PaperEvent[] }>(`/paper/events?${params.toString()}`);
    },
  });
}

/**
 * Live-appends `/topic/paper.events` frames into the {@link usePaperEvents} cache for the SAME filter
 * — newest-first, deduped by id. A frame that doesn't match the position/book filter is dropped. A
 * live event is always "today", so pass `live=false` when viewing a historical `day` (a static past
 * snapshot must not gain a live row), mirroring `useSignalsLive`.
 */
export function usePaperEventsLive(filter: PaperEventsFilter = {}, live = true) {
  const qc = useQueryClient();
  const { positionId, book, day } = filter;
  useEffect(() => {
    if (!live) return;
    const append = (body: string) => {
      let ev: PaperEvent;
      try {
        ev = JSON.parse(body) as PaperEvent;
      } catch {
        return; // unparseable frame — the REST snapshot heals
      }
      if (positionId != null && ev.positionId !== positionId) return;
      if (book && ev.book !== book) return;
      qc.setQueryData<{ items: PaperEvent[] }>(
        [PAPER_EVENTS, positionId ?? null, book ?? null, day ?? null],
        (prev) => {
          const items = prev?.items ?? [];
          if (items.some((i) => i.id === ev.id)) return prev; // dedupe a re-delivered frame
          return { items: [ev, ...items] };
        },
      );
    };
    const off = wsClient.topic('/topic/paper.events', append);
    return () => off();
  }, [qc, positionId, book, day, live]);
}
