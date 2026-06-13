import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';

/** One active overlay: an engine indicator id + its params (registry defaults + period override). */
export interface OverlayConfig {
  id: string;
  params: Record<string, unknown>;
}

interface ChartState {
  symbol: string;
  interval: string;
  overlays: OverlayConfig[];
}

const KEY = 'ay.chart';
const DEFAULTS: ChartState = { symbol: 'NSE:NIFTY 50', interval: '1d', overlays: [] };

function persist(state: ChartState): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(state));
  } catch {
    // private-mode / quota — chart state just does not persist
  }
}

/**
 * ChartStateStore (Phase 40C, E-10.2): the first-party chart-state persistence that replaces TV's
 * save/load — symbol, interval, overlay set + params persisted to localStorage (single user, no
 * server round-trip). Restores across a reload.
 */
export const ChartStateStore = signalStore(
  { providedIn: 'root' },
  withState<ChartState>(DEFAULTS),
  withMethods((store) => ({
    setSymbol(symbol: string): void {
      patchState(store, { symbol });
      persist({ symbol, interval: store.interval(), overlays: store.overlays() });
    },
    setInterval(interval: string): void {
      patchState(store, { interval });
      persist({ symbol: store.symbol(), interval, overlays: store.overlays() });
    },
    addOverlay(id: string, params: Record<string, unknown>): void {
      if (store.overlays().some((o) => o.id === id)) {
        return;
      }
      const overlays = [...store.overlays(), { id, params }];
      patchState(store, { overlays });
      persist({ symbol: store.symbol(), interval: store.interval(), overlays });
    },
    removeOverlay(id: string): void {
      const overlays = store.overlays().filter((o) => o.id !== id);
      patchState(store, { overlays });
      persist({ symbol: store.symbol(), interval: store.interval(), overlays });
    },
    updateParam(id: string, name: string, value: unknown): void {
      const overlays = store
        .overlays()
        .map((o) => (o.id === id ? { ...o, params: { ...o.params, [name]: value } } : o));
      patchState(store, { overlays });
      persist({ symbol: store.symbol(), interval: store.interval(), overlays });
    },
  })),
  withHooks({
    onInit(store) {
      try {
        const raw = localStorage.getItem(KEY);
        if (raw) {
          const saved = JSON.parse(raw) as Partial<ChartState>;
          patchState(store, {
            symbol: saved.symbol ?? DEFAULTS.symbol,
            interval: saved.interval ?? DEFAULTS.interval,
            overlays: saved.overlays ?? [],
          });
        }
      } catch {
        // corrupt blob — fall back to defaults
      }
    },
  }),
);
