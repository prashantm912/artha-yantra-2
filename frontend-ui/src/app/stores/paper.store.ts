import { inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { WsClientService } from '../core/ws-client.service';

/** An open paper position with its server-computed mark-to-market (recomputed live in the page). */
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

interface PaperState {
  positions: PaperPosition[];
  trades: PaperTrade[];
  pnl: PaperPnl | null;
  account: PaperAccount | null;
  risk: RiskSetting[];
  loading: boolean;
}

/**
 * PaperStore (F-43): the paper-trading ledger surface — open positions (mark-to-market), the
 * closed-trade ledger and the realized-equity curve. REST snapshot + reconnect re-fetch; live P&L
 * is recomputed in the page from the per-symbol tick map (§F.2), never the firehose.
 */
export const PaperStore = signalStore(
  { providedIn: 'root' },
  withState<PaperState>({
    positions: [],
    trades: [],
    pnl: null,
    account: null,
    risk: [],
    loading: false,
  }),
  withMethods((store, http = inject(HttpClient)) => ({
    loadAll(): void {
      patchState(store, { loading: true });
      http.get<{ items: PaperPosition[] }>('/api/v1/paper/positions').subscribe({
        next: (res) => patchState(store, { positions: res.items ?? [], loading: false }),
        error: () => patchState(store, { loading: false }),
      });
      http
        .get<{ items: PaperTrade[] }>('/api/v1/paper/trades', {
          params: new HttpParams().set('limit', 200),
        })
        .subscribe({
          next: (res) => patchState(store, { trades: res.items ?? [] }),
          error: () => undefined,
        });
      http.get<PaperPnl>('/api/v1/paper/pnl').subscribe({
        next: (pnl) => patchState(store, { pnl }),
        error: () => undefined,
      });
      this.loadAccount();
      this.loadRisk();
    },

    loadAccount(): void {
      http.get<PaperAccount>('/api/v1/paper/account').subscribe({
        next: (account) => patchState(store, { account }),
        error: () => undefined,
      });
    },

    loadRisk(): void {
      http.get<{ items: RiskSetting[] }>('/api/v1/risk/settings').subscribe({
        next: (res) => patchState(store, { risk: res.items ?? [] }),
        error: () => undefined,
      });
    },

    updateStartingCapital(startingCapital: string): void {
      http.put<PaperAccount>('/api/v1/paper/account', { startingCapital }).subscribe({
        next: (account) => patchState(store, { account }),
      });
    },

    updateRisk(key: string, value: Record<string, unknown>): void {
      http.put<{ items: RiskSetting[] }>('/api/v1/risk/settings', { key, value }).subscribe({
        next: (res) => patchState(store, { risk: res.items ?? [] }),
      });
    },

    /** Open a paper position (from a signal or manual). */
    openOrder(body: {
      signalId?: number;
      exchange?: string;
      tradingsymbol?: string;
      side?: string;
      qty: number;
      price?: string;
    }): void {
      http.post<PaperPosition>('/api/v1/paper/orders', body).subscribe({
        next: () => this.loadAll(),
      });
    },

    close(id: number, price?: string): void {
      http
        .post<PaperTrade>(`/api/v1/paper/positions/${id}/close`, price ? { price } : {})
        .subscribe({ next: () => this.loadAll() });
    },

    reset(confirm: boolean): void {
      http.post<void>('/api/v1/paper/reset', { confirm }).subscribe({ next: () => this.loadAll() });
    },
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      ws.reconnects$.subscribe(() => store.loadAll());
      store.loadAll();
    },
  }),
);
