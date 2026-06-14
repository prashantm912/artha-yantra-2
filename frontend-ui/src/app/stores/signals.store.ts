import { computed, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { ConflationBuffer } from '../core/conflation';
import { WsClientService } from '../core/ws-client.service';

/** One signal as both the REST snapshot and the live channel deliver it. */
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
}

/** The frozen C-2.6 contract as JSON. */
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

/** Recursive gate tree node. */
export interface GateNodeDto {
  kind: string;
  rule?: string;
  passed: boolean;
  operands?: Record<string, number | string | null>;
  children?: GateNodeDto[];
}

/** One scoring indicator line. */
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

/** The live feed keeps at most this many rows in memory (bounded ring, C-2.22). */
export const SIGNAL_RING_LIMIT = 200;

interface SignalsState {
  items: SignalDto[];
  loading: boolean;
  statusFilter: string | null;
  symbolFilter: string | null;
  selectedId: number | null;
  limit: number;
  offset: number;
}

/**
 * SignalsStore (C-2.22): history pages via REST + live unshift from the signals topic into a
 * BOUNDED ring buffer; on WS reconnect the snapshot re-fetches (at-least-once display).
 */
export const SignalsStore = signalStore(
  { providedIn: 'root' },
  withState<SignalsState>({
    items: [],
    loading: false,
    statusFilter: null,
    symbolFilter: null,
    selectedId: null,
    limit: 50,
    offset: 0,
  }),
  withComputed((store) => ({
    selected: computed(() => store.items().find((item) => item.id === store.selectedId()) ?? null),
    filtered: computed(() => {
      const status = store.statusFilter();
      const symbol = store.symbolFilter()?.toUpperCase();
      return store
        .items()
        .filter((item) => (status ? item.status === status : true))
        .filter((item) => (symbol ? item.tradingsymbol.toUpperCase().includes(symbol) : true));
    }),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    /** REST snapshot (also the reconnect gap-healer). */
    load(): void {
      patchState(store, { loading: true });
      let params = new HttpParams().set('limit', store.limit()).set('offset', store.offset());
      const status = store.statusFilter();
      if (status) {
        params = params.set('status', status);
      }
      http.get<{ items: SignalDto[] }>('/api/v1/signals', { params }).subscribe({
        next: (page) => patchState(store, { items: page.items, loading: false }),
        error: () => patchState(store, { loading: false }),
      });
    },

    /** Live batch from the conflated frame flush — newest first, deduped, ring-bounded. */
    ingestLive(batch: SignalDto[]): void {
      if (batch.length === 0) {
        return;
      }
      const known = new Set(store.items().map((item) => item.id));
      const fresh: SignalDto[] = [];
      for (const item of batch) {
        if (!known.has(item.id)) {
          known.add(item.id); // dedupe within the batch too (at-least-once delivery)
          fresh.push(item);
        }
      }
      if (fresh.length === 0) {
        return;
      }
      const merged = [...fresh.reverse(), ...store.items()].slice(0, SIGNAL_RING_LIMIT);
      patchState(store, { items: merged });
    },

    select(id: number | null): void {
      patchState(store, { selectedId: id });
    },

    setStatusFilter(status: string | null): void {
      patchState(store, { statusFilter: status, offset: 0 });
    },

    setSymbolFilter(symbol: string | null): void {
      patchState(store, { symbolFilter: symbol });
    },

    /** Owner actions round-trip then refresh the row; a qty opens a paper position (A12 prefill). */
    markTaken(id: number, qty?: number): void {
      http.post<SignalDto>(`/api/v1/signals/${id}/taken`, qty ? { qty } : {}).subscribe({
        next: (updated) => this.replace(updated),
      });
    },

    dismiss(id: number): void {
      http.post<SignalDto>(`/api/v1/signals/${id}/dismiss`, {}).subscribe({
        next: (updated) => this.replace(updated),
      });
    },

    replace(updated: SignalDto): void {
      patchState(store, {
        items: store.items().map((item) => (item.id === updated.id ? updated : item)),
      });
    },
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      // frames accumulate between animation frames; one flush per frame (C-2.25)
      const pending: SignalDto[] = [];
      const buffer = new ConflationBuffer<true>(() => {
        store.ingestLive([...pending]);
        pending.length = 0;
      });
      ws.topic('/topic/signals').subscribe((body) => {
        try {
          pending.push(JSON.parse(body) as SignalDto);
          buffer.set('signals', true);
        } catch {
          // unparseable frame — dropped, the REST snapshot heals
        }
      });
      ws.reconnects$.subscribe(() => store.load());
      store.load();
    },
  }),
);
