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
  loadingOptions: boolean;
  loadingFutures: boolean;
  loadingSpurt: boolean;
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
    loadingOptions: false,
    loadingFutures: false,
    loadingSpurt: false,
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
    };
  }),
);
