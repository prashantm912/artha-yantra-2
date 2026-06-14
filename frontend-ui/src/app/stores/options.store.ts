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
import { compareDecimal } from '../core/decimal';
import { WsClientService } from '../core/ws-client.service';

/** One strike side as the chain endpoint / `options.chain` topic delivers it (prices = strings). */
export interface OptionLeg {
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
  ivReason: string | null;
  priceSource: string | null;
}

/** One calls/strike/puts row. */
export interface OptionStrikeRow {
  strike: string;
  ce: OptionLeg | null;
  pe: OptionLeg | null;
}

/** The computed chain — the shape of both `GET /options/chain` and a `/options/chain/history` transform. */
export interface OptionChain {
  underlying: string;
  expiry: string;
  spot: string | null;
  forward: string | null;
  pcr: string | null;
  stale: boolean;
  asOf: string;
  rows: OptionStrikeRow[];
}

/** A flat per-leg row from `GET /options/chain/history` (the snapshot hypertable shape). */
export interface SnapshotRow {
  strike: string;
  optionType: 'CE' | 'PE';
  tradingsymbol: string;
  ltp: string | null;
  bid: string | null;
  ask: string | null;
  volume: number | null;
  oi: number | null;
  oiChange: number | null;
  spotPrice: string | null;
  iv: string | null;
  delta: string | null;
  gamma: string | null;
  theta: string | null;
  vega: string | null;
  rho: string | null;
  ivReason: string | null;
  priceSource: string | null;
  forwardPrice: string | null;
  riskFreeRate: string | null;
}

interface HistoryPayload {
  underlying: string;
  expiry: string;
  ts: string;
  rows: SnapshotRow[];
}

/** A single trailing-window VIX point (1d close). */
export interface VixPoint {
  bucket: string;
  close: string;
}

/** Level + trailing-window percentile for the India-VIX header chip. */
export interface VixStat {
  level: string;
  percentile: number | null;
  windowDays: number;
}

/** One day in the IV-history series (F-42B). */
export interface IvHistoryPoint {
  date: string;
  iv: string | null;
  atmIv: string | null;
  iv30d: string | null;
  spot: string | null;
}

/** The iv-history read (F-42B): trailing series + rank/percentile (null below the floor). */
export interface IvHistory {
  underlying: string;
  series: IvHistoryPoint[];
  currentIv: string | null;
  rank: string | null;
  percentile: number | null;
  windowDays: number;
  floorDays: number;
  insufficientHistory: boolean;
}

/** Bounded trailing PCR trend buffer (live deltas). */
export const PCR_TREND_LIMIT = 120;

interface OptionsState {
  underlying: string;
  expiry: string | null;
  expiries: string[];
  strikeWindow: number;
  activeTab: 'pcr' | 'smile' | 'oi' | 'iv';
  chain: OptionChain | null;
  loading: boolean;
  historyMode: boolean;
  historyAt: string | null;
  pcrTrend: { ts: string; pcr: string }[];
  vix: VixPoint[];
  ivHistory: IvHistory | null;
}

const DEFAULT_UNDERLYING = 'NIFTY 50';

/** Groups flat history rows into the same calls/strike/puts {@link OptionChain} the live endpoint serves. */
export function transformHistory(payload: HistoryPayload): OptionChain {
  const byStrike = new Map<string, OptionStrikeRow>();
  let spot: string | null = null;
  let ceOi = 0;
  let peOi = 0;
  for (const row of payload.rows) {
    spot ??= row.spotPrice;
    const existing = byStrike.get(row.strike) ?? { strike: row.strike, ce: null, pe: null };
    const leg: OptionLeg = {
      tradingsymbol: row.tradingsymbol,
      ltp: row.ltp,
      bid: row.bid,
      ask: row.ask,
      volume: row.volume,
      oi: row.oi,
      iv: row.iv,
      delta: row.delta,
      gamma: row.gamma,
      theta: row.theta,
      vega: row.vega,
      rho: row.rho,
      ivReason: row.ivReason,
      priceSource: row.priceSource,
    };
    if (row.optionType === 'CE') {
      existing.ce = leg;
      ceOi += row.oi ?? 0;
    } else {
      existing.pe = leg;
      peOi += row.oi ?? 0;
    }
    byStrike.set(row.strike, existing);
  }
  const rows = [...byStrike.values()].sort((a, b) => compareDecimal(a.strike, b.strike));
  return {
    underlying: payload.underlying,
    expiry: payload.expiry,
    spot,
    forward: payload.rows[0]?.forwardPrice ?? null,
    pcr: ceOi === 0 ? null : (peOi / ceOi).toFixed(4),
    stale: true,
    asOf: payload.ts,
    rows,
  };
}

/** Strikes ascending — the calls/strike/puts grid and ATM windowing both assume sorted rows. */
export function sortChain(chain: OptionChain): OptionChain {
  return { ...chain, rows: [...chain.rows].sort((a, b) => compareDecimal(a.strike, b.strike)) };
}

/** The strike closest to spot (ATM); null when spot is unknown. */
export function atmStrikeOf(rows: OptionStrikeRow[], spot: string | null): string | null {
  if (!spot || rows.length === 0) {
    return null;
  }
  let best = rows[0].strike;
  let bestDistance: number | null = null;
  for (const row of rows) {
    const distance = Math.abs(Number(row.strike) - Number(spot));
    if (bestDistance === null || distance < bestDistance) {
      bestDistance = distance;
      best = row.strike;
    }
  }
  return best;
}

/** Narrows rows to ±window strikes around ATM (window 0 = all). */
export function windowRows(
  rows: OptionStrikeRow[],
  spot: string | null,
  window: number,
): OptionStrikeRow[] {
  if (window <= 0 || rows.length === 0) {
    return rows;
  }
  const atm = atmStrikeOf(rows, spot);
  if (atm === null) {
    return rows;
  }
  const atmIndex = rows.findIndex((row) => row.strike === atm);
  const start = Math.max(0, atmIndex - window);
  return rows.slice(start, atmIndex + window + 1);
}

/** Trailing-window VIX level + percentile (share of window days strictly below the latest close). */
export function vixStat(points: VixPoint[]): VixStat | null {
  if (points.length === 0) {
    return null;
  }
  const level = points[points.length - 1].close;
  const below = points.filter((p) => compareDecimal(p.close, level) < 0).length;
  const percentile = points.length > 1 ? Math.round((below / points.length) * 100) : null;
  return { level, percentile, windowDays: points.length };
}

/**
 * OptionsStore (F-42): the live options workbench fed by the `options.chain` topic + the REST
 * snapshot (NEVER the tick firehose). History mode swaps in a stored snapshot; the India-VIX chip
 * and all analytics derive from these signals via {@link withComputed} — never persisted.
 */
export const OptionsStore = signalStore(
  { providedIn: 'root' },
  withState<OptionsState>({
    underlying: DEFAULT_UNDERLYING,
    expiry: null,
    expiries: [],
    strikeWindow: 10,
    activeTab: 'smile',
    chain: null,
    loading: false,
    historyMode: false,
    historyAt: null,
    pcrTrend: [],
    vix: [],
    ivHistory: null,
  }),
  withComputed((store) => ({
    spot: computed(() => store.chain()?.spot ?? null),
    pcr: computed(() => store.chain()?.pcr ?? null),
    stale: computed(() => store.chain()?.stale ?? false),
    atmStrike: computed(() => atmStrikeOf(store.chain()?.rows ?? [], store.chain()?.spot ?? null)),
    windowedRows: computed(() =>
      windowRows(store.chain()?.rows ?? [], store.chain()?.spot ?? null, store.strikeWindow()),
    ),
    vixStat: computed(() => vixStat(store.vix())),
  })),
  withMethods((store, http = inject(HttpClient)) => ({
    /** Live chain or stored snapshot, per the current mode (also the reconnect gap-healer). */
    load(): void {
      const underlying = store.underlying();
      const expiry = store.expiry();
      patchState(store, { loading: true });
      if (store.historyMode()) {
        let params = new HttpParams().set('underlying', underlying);
        if (expiry) {
          params = params.set('expiry', expiry);
        }
        if (store.historyAt()) {
          params = params.set('at', store.historyAt() as string);
        }
        http.get<HistoryPayload>('/api/v1/market/options/chain/history', { params }).subscribe({
          next: (payload) =>
            patchState(store, { chain: transformHistory(payload), loading: false }),
          error: () => patchState(store, { chain: null, loading: false }),
        });
        return;
      }
      let params = new HttpParams().set('underlying', underlying);
      if (expiry) {
        params = params.set('expiry', expiry);
      }
      http.get<OptionChain>('/api/v1/market/options/chain', { params }).subscribe({
        next: (chain) => patchState(store, { chain: sortChain(chain), loading: false }),
        error: () => patchState(store, { chain: null, loading: false }),
      });
    },

    /** Available expiries for the current underlying; defaults the selection to the nearest. */
    loadExpiries(): void {
      http
        .get<string[]>(`/api/v1/instruments/${encodeURIComponent(store.underlying())}/expiries`)
        .subscribe({
          next: (expiries) => {
            patchState(store, {
              expiries: expiries ?? [],
              expiry: store.expiry() ?? expiries?.[0] ?? null,
            });
            this.load();
          },
          error: () => patchState(store, { expiries: [] }),
        });
    },

    loadVix(): void {
      const to = new Date();
      const from = new Date(to.getTime() - 400 * 86_400_000);
      const params = new HttpParams()
        .set('exchange', 'NSE')
        .set('tradingsymbol', 'INDIA VIX')
        .set('interval', '1d')
        .set('from', from.toISOString())
        .set('to', to.toISOString())
        .set('limit', 400);
      http
        .get<{ items: { bucket: string; close: string }[] }>('/api/v1/market/candles', { params })
        .subscribe({
          next: (res) =>
            patchState(store, {
              vix: (res.items ?? []).map((c) => ({ bucket: c.bucket, close: c.close })),
            }),
          error: () => undefined,
        });
    },

    /** Trailing-1-year IV history + rank/percentile (F-42B); the badge + IV tab read it. */
    loadIvHistory(): void {
      http
        .get<IvHistory>('/api/v1/market/options/iv-history', {
          params: new HttpParams().set('underlying', store.underlying()),
        })
        .subscribe({
          next: (ivHistory) => patchState(store, { ivHistory }),
          error: () => patchState(store, { ivHistory: null }),
        });
    },

    setUnderlying(underlying: string): void {
      patchState(store, { underlying, expiry: null, expiries: [], pcrTrend: [], ivHistory: null });
      this.loadExpiries();
      this.loadIvHistory();
    },

    setExpiry(expiry: string | null): void {
      patchState(store, { expiry, pcrTrend: [] });
      this.load();
    },

    setStrikeWindow(strikeWindow: number): void {
      patchState(store, { strikeWindow });
    },

    setActiveTab(activeTab: 'pcr' | 'smile' | 'oi' | 'iv'): void {
      patchState(store, { activeTab });
    },

    setHistoryMode(historyMode: boolean): void {
      patchState(store, { historyMode });
      this.load();
    },

    setHistoryAt(historyAt: string | null): void {
      patchState(store, { historyAt });
      if (store.historyMode()) {
        this.load();
      }
    },

    /** A live chain frame — applied only when it matches the watched (underlying, expiry). */
    ingestLive(chain: OptionChain): void {
      if (store.historyMode() || chain.underlying !== store.underlying()) {
        return;
      }
      if (store.expiry() && chain.expiry !== store.expiry()) {
        return;
      }
      const trend = chain.pcr
        ? [...store.pcrTrend(), { ts: chain.asOf, pcr: chain.pcr }].slice(-PCR_TREND_LIMIT)
        : store.pcrTrend();
      patchState(store, { chain: sortChain(chain), pcrTrend: trend });
    },
  })),
  withHooks({
    onInit(store) {
      const ws = inject(WsClientService);
      ws.topic('/topic/options.chain').subscribe((body) => {
        try {
          store.ingestLive(JSON.parse(body) as OptionChain);
        } catch {
          // unparseable frame — the REST snapshot heals
        }
      });
      ws.reconnects$.subscribe(() => store.load());
      store.loadExpiries();
      store.loadVix();
      store.loadIvHistory();
    },
  }),
);
