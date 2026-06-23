// Paper-trading cockpit data layer (master plan §20 parity / F-43). Ports Angular's PaperStore:
// open positions with server-computed mark-to-market, the closed-trade ledger, the realized-equity
// curve, the account header and the global risk limits (kill switch / max-open / daily-loss). The MTM
// stays fresh via a short refetchInterval (the BE computes markPrice/unrealizedPnl server-side); the
// WS per-symbol tick overlay the Angular page layered on top is a later enhancement.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

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

export function usePaperPositions() {
  return useQuery({
    queryKey: [PAPER, 'positions'],
    queryFn: () => apiFetch<{ items: PaperPosition[] }>('/paper/positions'),
    refetchInterval: MTM_REFETCH_MS,
  });
}

export function usePaperTrades() {
  return useQuery({
    queryKey: [PAPER, 'trades'],
    queryFn: () => apiFetch<{ items: PaperTrade[] }>('/paper/trades?limit=200'),
  });
}

export function usePaperPnl() {
  return useQuery({
    queryKey: [PAPER, 'pnl'],
    queryFn: () => apiFetch<PaperPnl>('/paper/pnl'),
  });
}

export function usePaperAccount() {
  return useQuery({
    queryKey: [PAPER, 'account'],
    queryFn: () => apiFetch<PaperAccount>('/paper/account'),
    refetchInterval: MTM_REFETCH_MS,
  });
}

export function useRiskSettings() {
  return useQuery({
    queryKey: [RISK, 'settings'],
    queryFn: () => apiFetch<{ items: RiskSetting[] }>('/risk/settings'),
  });
}

/** Invalidates every paper query (after a mutation that changes the ledger). */
function usePaperInvalidate() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: [PAPER] });
  };
}

export function useUpdateCapital() {
  const invalidate = usePaperInvalidate();
  return useMutation({
    mutationFn: (startingCapital: string) =>
      apiFetch<PaperAccount>('/paper/account', { method: 'PUT', json: { startingCapital } }),
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

export function useResetLedger() {
  const invalidate = usePaperInvalidate();
  return useMutation({
    mutationFn: () => apiFetch<void>('/paper/reset', { method: 'POST', json: { confirm: true } }),
    onSuccess: invalidate,
  });
}

export function useUpdateRisk() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: Record<string, unknown> }) =>
      apiFetch<{ items: RiskSetting[] }>('/risk/settings', { method: 'PUT', json: { key, value } }),
    onSuccess: (res) => qc.setQueryData([RISK, 'settings'], res),
  });
}

/** True when a named risk limit is enabled. */
export function riskEnabled(items: RiskSetting[] | undefined, key: string): boolean {
  return items?.find((r) => r.key === key)?.value?.['enabled'] === true;
}
