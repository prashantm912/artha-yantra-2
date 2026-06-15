import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';

/** The OiInterval tokens the analytics endpoints accept (note `60m`, not `1h`). */
export const OI_INTERVALS = ['1m', '3m', '5m', '15m', '30m', '60m'] as const;
export type OiInterval = (typeof OI_INTERVALS)[number];

export type OiMode = 'live' | 'history';

interface SymbolContextState {
  /** Underlying name (the `name` query param of every OI endpoint), not an EXCHANGE:SYMBOL key. */
  name: string;
  /** ISO yyyy-MM-dd expiry; required by the options endpoints, ignored by futures. */
  expiry: string | null;
  interval: OiInterval;
  mode: OiMode;
  /** ISO yyyy-MM-dd; required when `mode === 'history'`. */
  date: string | null;
  /** Expiry options for the current underlying. */
  expiries: string[];
}

const NAME_KEY = 'ay.oi.name';
const EXPIRY_KEY = 'ay.oi.expiry';
const INTERVAL_KEY = 'ay.oi.interval';

function persist(key: string, value: string | null): void {
  try {
    if (value === null) {
      localStorage.removeItem(key);
    } else {
      localStorage.setItem(key, value);
    }
  } catch {
    // private-mode / quota — selection simply does not persist across reloads
  }
}

function storedInterval(): OiInterval {
  const raw = localStorage.getItem(INTERVAL_KEY);
  return (OI_INTERVALS as readonly string[]).includes(raw ?? '') ? (raw as OiInterval) : '3m';
}

/**
 * SymbolContextStore: the universal control-bar selection (oipulse's Mode·Name·Date·Expiry·Interval
 * that "carries everywhere"). A root singleton so every OI-analytics page shares one selection;
 * name/expiry/interval persist to localStorage, mode/date are session-transient. Owns the expiry
 * option list for the current underlying.
 */
export const SymbolContextStore = signalStore(
  { providedIn: 'root' },
  withState<SymbolContextState>({
    name: 'NIFTY 50',
    expiry: null,
    interval: '3m',
    mode: 'live',
    date: null,
    expiries: [],
  }),
  withMethods((store, http = inject(HttpClient)) => ({
    /** Loads expiries for the current underlying; defaults the selected expiry to the nearest. */
    loadExpiries(): void {
      const name = store.name();
      http.get<string[]>(`/api/v1/instruments/${encodeURIComponent(name)}/expiries`).subscribe({
        next: (expiries) => {
          const list = expiries ?? [];
          patchState(store, { expiries: list });
          if (!store.expiry() && list.length > 0) {
            patchState(store, { expiry: list[0] });
            persist(EXPIRY_KEY, list[0]);
          }
        },
        error: () => patchState(store, { expiries: [] }),
      });
    },

    setName(name: string): void {
      patchState(store, { name, expiry: null, expiries: [] });
      persist(NAME_KEY, name);
      persist(EXPIRY_KEY, null);
      this.loadExpiries();
    },

    setExpiry(expiry: string | null): void {
      patchState(store, { expiry });
      persist(EXPIRY_KEY, expiry);
    },

    setInterval(interval: OiInterval): void {
      patchState(store, { interval });
      persist(INTERVAL_KEY, interval);
    },

    setMode(mode: OiMode): void {
      patchState(store, { mode });
    },

    setDate(date: string | null): void {
      patchState(store, { date });
    },
  })),
  withHooks({
    onInit(store) {
      patchState(store, {
        name: localStorage.getItem(NAME_KEY) ?? store.name(),
        expiry: localStorage.getItem(EXPIRY_KEY) ?? store.expiry(),
        interval: storedInterval(),
      });
      store.loadExpiries();
    },
  }),
);
