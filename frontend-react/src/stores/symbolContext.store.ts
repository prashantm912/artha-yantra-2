import { create } from 'zustand';

// SymbolContextStore (master plan §20 / §11.2): the universal control-bar selection
// (Mode·Name·Date·Expiry·Interval) shared across every OI page. name/expiry/interval persist to
// localStorage; mode/date are session-transient. The expiry LIST is NOT selection state — it's a
// TanStack query (useExpiries); setName clears expiry so the new underlying's list reloads.

// 60m, NOT 1h. 10m rides the OiInterval.M10 BE enum; pages opt in via allowedIntervals (only
// Connecting Dots offers it today), so adding it here doesn't change the other OI pages' UI.
export const OI_INTERVALS = ['1m', '3m', '5m', '10m', '15m', '30m', '60m'] as const;
export type OiInterval = (typeof OI_INTERVALS)[number];
export type OiMode = 'live' | 'history';

const NAME_KEY = 'ay.oi.name';
const EXPIRY_KEY = 'ay.oi.expiry';
const INTERVAL_KEY = 'ay.oi.interval';

function persist(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    /* private mode / quota */
  }
}

function load(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storedInterval(): OiInterval {
  const raw = load(INTERVAL_KEY);
  return (OI_INTERVALS as readonly string[]).includes(raw ?? '') ? (raw as OiInterval) : '3m';
}

interface SymbolContextState {
  name: string;
  expiry: string | null;
  interval: OiInterval;
  mode: OiMode;
  date: string | null;
  setName(name: string): void;
  setExpiry(expiry: string | null): void;
  setInterval(interval: OiInterval): void;
  setMode(mode: OiMode): void;
  setDate(date: string | null): void;
}

export const useSymbolContext = create<SymbolContextState>((set) => ({
  name: load(NAME_KEY) ?? 'NIFTY 50',
  expiry: load(EXPIRY_KEY),
  interval: storedInterval(),
  mode: 'live',
  date: null,

  setName(name: string) {
    set({ name, expiry: null }); // new underlying → its expiry list reloads, default re-applies
    persist(NAME_KEY, name);
    persist(EXPIRY_KEY, null);
  },
  setExpiry(expiry: string | null) {
    set({ expiry });
    persist(EXPIRY_KEY, expiry);
  },
  setInterval(interval: OiInterval) {
    set({ interval });
    persist(INTERVAL_KEY, interval);
  },
  setMode(mode: OiMode) {
    set({ mode });
  },
  setDate(date: string | null) {
    set({ date });
  },
}));
