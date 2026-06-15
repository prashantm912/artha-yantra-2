import { computed, inject } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { compareDecimal } from '../core/decimal';
import { type OiInterpretation } from '../core/oi-interpretation';
import { SILENCE_ERROR_TOAST } from '../core/error.interceptor';
import { SymbolContextStore } from './symbol-context.store';

// --- wire types (Phase-2 endpoints). BigDecimal fields are JSON STRINGS at runtime (Jackson),
// despite the generated `.d.ts` typing them as `number`; `long` OI fields stay numbers. ---

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

/** One row of GET /api/v1/market/futures/oi-analysis `{items}` (per contract). */
export interface FuturesOiPoint {
  bucket: string;
  tradingsymbol: string;
  ltp: string | null;
  oi: number | null;
  oiChange: number | null;
}

/** One row of GET /api/v1/market/options/spurt `{items}` — per strike·side interval buildup. */
export interface SpurtRow {
  strike: string;
  optionType: 'CE' | 'PE';
  ltp: string | null;
  oi: number | null;
  oiChange: number;
  spurtPct: string | null;
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

// --- Stage-G endpoints (decimal fields are JSON strings; long OI fields are numbers) ---

/** One row of GET /api/v1/market/options/big-oi. */
export interface BigOiRow {
  strike: string;
  optionType: 'CE' | 'PE';
  oi: number;
  oiChange: number;
  ltp: string | null;
}
export interface BigOi {
  items: BigOiRow[];
  asOf: string | null;
}

/** GET /api/v1/market/options/premium — per-strike straddle premium + the ATM straddle. */
export interface PremiumRow {
  strike: string;
  straddle: string;
  ce: string;
  pe: string;
}
export interface PremiumChain {
  items: PremiumRow[];
  atmStrike: string | null;
  atmStraddle: string | null;
  spot: string | null;
  asOf: string | null;
}

/** One point of GET /api/v1/market/options/premium-series — the ATM straddle per bucket. */
export interface PremiumSeriesPoint {
  bucket: string;
  atmStrike: string | null;
  atmStraddle: string | null;
  spot: string | null;
}
export interface PremiumSeries {
  items: PremiumSeriesPoint[];
  asOf: string | null;
}

/** GET /api/v1/market/options/trending — per-bucket OI trend. */
export interface TrendPoint {
  bucket: string;
  totalOi: number;
  ceOi: number;
  peOi: number;
  trend: 'UP' | 'DOWN' | 'FLAT';
}
export interface TrendSeries {
  items: TrendPoint[];
  asOf: string | null;
}

/** One row of GET /api/v1/market/futures/spurt. */
export interface FutSpurt {
  tradingsymbol: string;
  ltp: string | null;
  oi: number;
  oiChange: number;
  spurtPct: string | null;
  interpretation: OiInterpretation;
}
export interface FutSpurtChain {
  items: FutSpurt[];
  asOf: string | null;
}

/** GET /api/v1/market/futures/movers — gainers/losers by price% + OI%. */
export interface MoverRow {
  tradingsymbol: string;
  ltp: string | null;
  pricePct: string | null;
  oiPct: string | null;
  interpretation: OiInterpretation;
}
export interface Movers {
  gainers: MoverRow[];
  losers: MoverRow[];
  asOf: string | null;
}

/** GET /api/v1/market/futures/banks — the index's futures term structure + calendar-spread basis. */
export interface BankRow {
  tradingsymbol: string;
  expiry: string | null;
  ltp: string | null;
  oi: number;
  oiChange: number;
  basis: string | null;
  interpretation: OiInterpretation | null;
}
export interface Banks {
  items: BankRow[];
  asOf: string | null;
}

/** GET /api/v1/market/futures/buzz — a time x contract 4-state heatmap (null cell = no signal). */
export interface BuzzMatrix {
  contracts: string[];
  buckets: string[];
  cells: (OiInterpretation | null)[][];
  asOf: string | null;
}

/** One row of GET /api/v1/market/futures/eod. */
export interface EodRow {
  tradingsymbol: string;
  tradeDate: string;
  open: string | null;
  high: string | null;
  low: string | null;
  close: string | null;
  oiClose: number;
  oiChange: number;
  volume: number;
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

/** Folds flat per-leg points into one CE/PE row per strike, sorted ascending by strike. */
export function foldStrikes(points: OiStrikePoint[]): OiChainRow[] {
  const byStrike = new Map<string, OiChainRow>();
  for (const p of points) {
    let row = byStrike.get(p.strike);
    if (!row) {
      row = { strike: p.strike, ce: null, pe: null, spot: p.spot };
      byStrike.set(p.strike, row);
    }
    const cell: LegCell = { oi: p.oi, oiChange: p.oiChange, iv: p.iv, ltp: p.ltp };
    if (p.optionType === 'CE') {
      row.ce = cell;
    } else {
      row.pe = cell;
    }
    if (p.spot !== null && p.spot !== undefined) {
      row.spot = p.spot;
    }
  }
  return [...byStrike.values()].sort((a, b) => compareDecimal(a.strike, b.strike));
}

interface OiAnalyticsState {
  oiStats: OiStats | null;
  activeStrikes: ActiveStrikes | null;
  strikes: OiStrikePoint[];
  futures: FuturesOiPoint[];
  spurt: SpurtChain | null;
  bigOi: BigOi | null;
  premium: PremiumChain | null;
  premiumSeries: PremiumSeries | null;
  trend: TrendSeries | null;
  futSpurt: FutSpurtChain | null;
  movers: Movers | null;
  banks: Banks | null;
  buzz: BuzzMatrix | null;
  eod: EodRow[];
  loadingOptions: boolean;
  loadingFutures: boolean;
  loadingSpurt: boolean;
  loadingBigOi: boolean;
  loadingTrend: boolean;
  loadingPremiumSeries: boolean;
  loadingFutSpurt: boolean;
  loadingMovers: boolean;
  loadingBuzz: boolean;
  loadingEod: boolean;
}

/** Context where an empty result (DATA_GAP / no snapshot) is normal — suppress the error toast. */
const SILENT = new HttpContext().set(SILENCE_ERROR_TOAST, true);

/**
 * OiAnalyticsStore (Phase 4 archetype-1): REST reads of the four Phase-2 OI endpoints driven by
 * {@link SymbolContextStore}'s selection. Prices/IV/PCR are decimal STRINGS (never `parseFloat`);
 * `oi-stats`/`active-strikes` 422 DATA_GAP on no-data is a normal empty state (toast suppressed).
 */
export const OiAnalyticsStore = signalStore(
  { providedIn: 'root' },
  withState<OiAnalyticsState>({
    oiStats: null,
    activeStrikes: null,
    strikes: [],
    futures: [],
    spurt: null,
    bigOi: null,
    premium: null,
    premiumSeries: null,
    trend: null,
    futSpurt: null,
    movers: null,
    banks: null,
    buzz: null,
    eod: [],
    loadingOptions: false,
    loadingFutures: false,
    loadingSpurt: false,
    loadingBigOi: false,
    loadingTrend: false,
    loadingPremiumSeries: false,
    loadingFutSpurt: false,
    loadingMovers: false,
    loadingBuzz: false,
    loadingEod: false,
  }),
  withComputed((store) => ({
    chainRows: computed(() => foldStrikes(store.strikes())),
    spot: computed(() => store.strikes().find((p) => p.spot != null)?.spot ?? null),
    // reduce (not spread) — a wide chain could exceed the Math.max arg-count limit.
    maxOptionOi: computed(() => store.strikes().reduce((m, p) => Math.max(m, p.oi ?? 0), 1)),
    maxFuturesOi: computed(() => store.futures().reduce((m, f) => Math.max(m, f.oi ?? 0), 1)),
    // ΔOI is orders of magnitude smaller than total OI — scale its bar against |ΔOI|, not OI.
    maxFuturesOiChange: computed(() =>
      store.futures().reduce((m, f) => Math.max(m, Math.abs(f.oiChange ?? 0)), 1),
    ),
    // The underlying 4-state OI-Interpretation (spurt rollup) — null until two buckets accrue.
    oiInterpretation: computed(() => store.spurt()?.summary?.interpretation ?? null),
  })),
  withMethods((store, http = inject(HttpClient), ctx = inject(SymbolContextStore)) => {
    // Per-loader generation tokens: a response is applied only if its selection is still current,
    // so a slow earlier request cannot overwrite a newer selection's data (symbol-switch race).
    let optGen = 0;
    let futGen = 0;
    let spurtGen = 0;
    let bigOiGen = 0;
    let premiumGen = 0;
    let premiumSeriesGen = 0;
    let trendGen = 0;
    let futSpurtGen = 0;
    let moversGen = 0;
    let banksGen = 0;
    let buzzGen = 0;
    let eodGen = 0;

    function params(includeExpiry: boolean): HttpParams {
      let p = new HttpParams()
        .set('mode', ctx.mode())
        .set('name', ctx.name())
        .set('interval', ctx.interval());
      const date = ctx.date();
      if (date) {
        p = p.set('date', date);
      }
      const expiry = ctx.expiry();
      if (includeExpiry && expiry) {
        p = p.set('expiry', expiry);
      }
      return p;
    }

    /** True when the current selection cannot produce a valid query (skip the request, no toast). */
    function unsatisfiable(needExpiry: boolean): boolean {
      if (!ctx.name()) {
        return true;
      }
      if (needExpiry && !ctx.expiry()) {
        return true;
      }
      // history mode requires a date (backend 400s otherwise — known-invalid, don't fire it).
      return ctx.mode() === 'history' && !ctx.date();
    }

    return {
      /** Options OI: PCR/max-pain stats + active-strike sentiment + the per-strike grid. */
      loadOptions(): void {
        if (unsatisfiable(true)) {
          patchState(store, {
            oiStats: null,
            activeStrikes: null,
            strikes: [],
            loadingOptions: false,
          });
          return;
        }
        const g = ++optGen;
        const p = params(true);
        patchState(store, { loadingOptions: true });
        http
          .get<OiStats>('/api/v1/market/options/oi-stats', { params: p, context: SILENT })
          .subscribe({
            next: (oiStats) => g === optGen && patchState(store, { oiStats }),
            error: () => g === optGen && patchState(store, { oiStats: null }),
          });
        http
          .get<ActiveStrikes>('/api/v1/market/options/active-strikes', {
            params: p,
            context: SILENT,
          })
          .subscribe({
            next: (activeStrikes) => g === optGen && patchState(store, { activeStrikes }),
            error: () => g === optGen && patchState(store, { activeStrikes: null }),
          });
        http
          .get<{ items: OiStrikePoint[] }>('/api/v1/market/options/oi-analysis', { params: p })
          .subscribe({
            next: (res) =>
              g === optGen &&
              patchState(store, { strikes: res.items ?? [], loadingOptions: false }),
            error: () => g === optGen && patchState(store, { strikes: [], loadingOptions: false }),
          });
      },

      /** Futures OI: per-contract OI/ΔOI/LTP (expiry ignored by the endpoint). */
      loadFutures(): void {
        if (unsatisfiable(false)) {
          patchState(store, { futures: [], loadingFutures: false });
          return;
        }
        const g = ++futGen;
        patchState(store, { loadingFutures: true });
        http
          .get<{ items: FuturesOiPoint[] }>('/api/v1/market/futures/oi-analysis', {
            params: params(false),
          })
          .subscribe({
            next: (res) =>
              g === futGen &&
              patchState(store, { futures: res.items ?? [], loadingFutures: false }),
            error: () => g === futGen && patchState(store, { futures: [], loadingFutures: false }),
          });
      },

      /** Options OI spurt: per-strike interval buildup + the underlying 4-state rollup. */
      loadSpurt(): void {
        if (unsatisfiable(true)) {
          patchState(store, { spurt: null, loadingSpurt: false });
          return;
        }
        const g = ++spurtGen;
        patchState(store, { loadingSpurt: true });
        http
          .get<SpurtChain>('/api/v1/market/options/spurt', {
            params: params(true),
            context: SILENT,
          })
          .subscribe({
            next: (spurt) => g === spurtGen && patchState(store, { spurt, loadingSpurt: false }),
            error: () => g === spurtGen && patchState(store, { spurt: null, loadingSpurt: false }),
          });
      },

      /** Options Big-OI: the latest bucket's legs ranked by |interval ΔOI|. */
      loadBigOi(): void {
        if (unsatisfiable(true)) {
          patchState(store, { bigOi: null, loadingBigOi: false });
          return;
        }
        const g = ++bigOiGen;
        patchState(store, { loadingBigOi: true });
        http
          .get<BigOi>('/api/v1/market/options/big-oi', { params: params(true), context: SILENT })
          .subscribe({
            next: (bigOi) => g === bigOiGen && patchState(store, { bigOi, loadingBigOi: false }),
            error: () => g === bigOiGen && patchState(store, { bigOi: null, loadingBigOi: false }),
          });
      },

      /** Options premium: per-strike straddle premium + the ATM straddle. */
      loadPremium(): void {
        if (unsatisfiable(true)) {
          patchState(store, { premium: null });
          return;
        }
        const g = ++premiumGen;
        http
          .get<PremiumChain>('/api/v1/market/options/premium', {
            params: params(true),
            context: SILENT,
          })
          .subscribe({
            next: (premium) => g === premiumGen && patchState(store, { premium }),
            error: () => g === premiumGen && patchState(store, { premium: null }),
          });
      },

      /** Options ATM-straddle premium decay over the last N buckets (the premium chart series). */
      loadPremiumSeries(): void {
        if (unsatisfiable(true)) {
          patchState(store, { premiumSeries: null, loadingPremiumSeries: false });
          return;
        }
        const g = ++premiumSeriesGen;
        patchState(store, { loadingPremiumSeries: true });
        http
          .get<PremiumSeries>('/api/v1/market/options/premium-series', {
            params: params(true),
            context: SILENT,
          })
          .subscribe({
            next: (premiumSeries) =>
              g === premiumSeriesGen &&
              patchState(store, { premiumSeries, loadingPremiumSeries: false }),
            error: () =>
              g === premiumSeriesGen &&
              patchState(store, { premiumSeries: null, loadingPremiumSeries: false }),
          });
      },

      /** Options OI-trend over the last N buckets. */
      loadTrending(): void {
        if (unsatisfiable(true)) {
          patchState(store, { trend: null, loadingTrend: false });
          return;
        }
        const g = ++trendGen;
        patchState(store, { loadingTrend: true });
        http
          .get<TrendSeries>('/api/v1/market/options/trending', {
            params: params(true),
            context: SILENT,
          })
          .subscribe({
            next: (trend) => g === trendGen && patchState(store, { trend, loadingTrend: false }),
            error: () => g === trendGen && patchState(store, { trend: null, loadingTrend: false }),
          });
      },

      /** Futures interval buildup (per contract). */
      loadFutSpurt(): void {
        if (unsatisfiable(false)) {
          patchState(store, { futSpurt: null, loadingFutSpurt: false });
          return;
        }
        const g = ++futSpurtGen;
        patchState(store, { loadingFutSpurt: true });
        http
          .get<FutSpurtChain>('/api/v1/market/futures/spurt', {
            params: params(false),
            context: SILENT,
          })
          .subscribe({
            next: (futSpurt) =>
              g === futSpurtGen && patchState(store, { futSpurt, loadingFutSpurt: false }),
            error: () =>
              g === futSpurtGen && patchState(store, { futSpurt: null, loadingFutSpurt: false }),
          });
      },

      /** Futures gainers/losers by price% + OI%. */
      loadMovers(): void {
        if (unsatisfiable(false)) {
          patchState(store, { movers: null, loadingMovers: false });
          return;
        }
        const g = ++moversGen;
        patchState(store, { loadingMovers: true });
        http
          .get<Movers>('/api/v1/market/futures/movers', { params: params(false), context: SILENT })
          .subscribe({
            next: (movers) =>
              g === moversGen && patchState(store, { movers, loadingMovers: false }),
            error: () =>
              g === moversGen && patchState(store, { movers: null, loadingMovers: false }),
          });
      },

      /** Futures term structure (calendar-spread basis) for the queried index. */
      loadBanks(): void {
        if (unsatisfiable(false)) {
          patchState(store, { banks: null });
          return;
        }
        const g = ++banksGen;
        http
          .get<Banks>('/api/v1/market/futures/banks', { params: params(false), context: SILENT })
          .subscribe({
            next: (banks) => g === banksGen && patchState(store, { banks }),
            error: () => g === banksGen && patchState(store, { banks: null }),
          });
      },

      /** Futures buzz: time x contract 4-state heatmap. */
      loadBuzz(): void {
        if (unsatisfiable(false)) {
          patchState(store, { buzz: null, loadingBuzz: false });
          return;
        }
        const g = ++buzzGen;
        patchState(store, { loadingBuzz: true });
        http
          .get<BuzzMatrix>('/api/v1/market/futures/buzz', {
            params: params(false),
            context: SILENT,
          })
          .subscribe({
            next: (buzz) => g === buzzGen && patchState(store, { buzz, loadingBuzz: false }),
            error: () => g === buzzGen && patchState(store, { buzz: null, loadingBuzz: false }),
          });
      },

      /** Futures EOD daily OHLC+OI rollup over [from, to] (date-driven, not the interval model). */
      loadEod(from: string, to?: string): void {
        if (!ctx.name() || !from) {
          patchState(store, { eod: [], loadingEod: false });
          return;
        }
        const g = ++eodGen;
        patchState(store, { loadingEod: true });
        let p = new HttpParams().set('name', ctx.name()).set('from', from);
        if (to) {
          p = p.set('to', to);
        }
        http
          .get<{ items: EodRow[] }>('/api/v1/market/futures/eod', { params: p, context: SILENT })
          .subscribe({
            next: (res) =>
              g === eodGen && patchState(store, { eod: res.items ?? [], loadingEod: false }),
            error: () => g === eodGen && patchState(store, { eod: [], loadingEod: false }),
          });
      },
    };
  }),
);
