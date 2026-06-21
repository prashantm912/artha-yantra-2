import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, listItems } from './client.ts';
import { useSymbolContext } from '../stores/symbolContext.store.ts';
import type {
  ActiveStrikes,
  ChainTable,
  OiStats,
  OiStrikePoint,
  SpurtChain,
  StraddleChart,
  StrikeSeries,
  VixQuote,
} from './types.ts';

// OI-analytics query hooks (master plan §20 / §11.2). The Angular store hand-rolled 13 generation
// counters for stale-drop — TanStack Query keys subsume that (a late response for an old key can't
// overwrite the active key), so the counters are NOT ported. 422 DATA_GAP is a normal empty state →
// silenced + mapped to the empty shape (no toast, the page renders its empty-state copy).

interface OiCtx {
  name: string;
  expiry: string | null;
  interval: string;
  mode: string;
  date: string | null;
}

/** Reads the shared selection as individual primitives (stable for query keys). */
function useOiCtx(): OiCtx {
  return {
    name: useSymbolContext((s) => s.name),
    expiry: useSymbolContext((s) => s.expiry),
    interval: useSymbolContext((s) => s.interval),
    mode: useSymbolContext((s) => s.mode),
    date: useSymbolContext((s) => s.date),
  };
}

function oiParams(ctx: OiCtx, includeExpiry: boolean): string {
  const p = new URLSearchParams({ mode: ctx.mode, name: ctx.name, interval: ctx.interval });
  if (ctx.date) p.set('date', ctx.date);
  if (includeExpiry && ctx.expiry) p.set('expiry', ctx.expiry);
  return p.toString();
}

/** Mirrors the Angular `unsatisfiable` guard → TanStack's `enabled`. */
function satisfiable(ctx: OiCtx, needExpiry: boolean): boolean {
  if (!ctx.name) return false;
  if (needExpiry && !ctx.expiry) return false;
  return !(ctx.mode === 'history' && !ctx.date); // history requires a date (backend 400s)
}

/** GET that maps 422 DATA_GAP → the empty shape (no throw → no toast). */
async function oiGet<T>(path: string, params: string, emptyOn422: T): Promise<T> {
  try {
    return await apiFetch<T>(`${path}?${params}`, { silenceToast: true });
  } catch (err) {
    if (err instanceof ApiError && err.status === 422) return emptyOn422;
    throw err;
  }
}

export function useOiStats() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'oi-stats', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () => oiGet<OiStats | null>('/market/options/oi-stats', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

export function useActiveStrikes() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'active-strikes', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<ActiveStrikes | null>('/market/options/active-strikes', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

/** The per-strike chain points (the `{items}` of oi-analysis). */
export function useOiAnalysis() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'oi-analysis', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: async () => {
      const res = await oiGet<{ items?: OiStrikePoint[] }>(
        '/market/options/oi-analysis',
        oiParams(ctx, true),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: satisfiable(ctx, true),
  });
}

export function useOptionsSpurt() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'spurt', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () => oiGet<SpurtChain | null>('/market/options/spurt', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

/** The true Options OI Analysis feed (§20.7.5): one strike's CE+PE points across the session buckets. */
export function useStrikeSeries(strike: string | null) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'strike-series', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date, strike],
    queryFn: () =>
      oiGet<StrikeSeries | null>(
        '/market/options/oi-analysis/strike-series',
        `${oiParams(ctx, true)}&strike=${encodeURIComponent(strike ?? '')}`,
        null,
      ),
    enabled: satisfiable(ctx, true) && !!strike,
  });
}

/**
 * Straddle/Strangle chart feed (§20.7.6): combined CE+PE premium candles for a strike over the
 * session. The straddle interval is RAW MINUTES (parsed from the shared OiInterval token — 10m is the
 * one documented gap), and the BE requires a base {@code strike}; a strangle adds call/put overrides.
 */
export function useStraddleChart(
  strike: string | null,
  callStrike: string | null,
  putStrike: string | null,
) {
  const ctx = useOiCtx();
  const minutes = parseInt(ctx.interval, 10) || 3;
  return useQuery({
    queryKey: [
      'oi',
      'straddle',
      ctx.name,
      ctx.expiry,
      ctx.interval,
      ctx.mode,
      ctx.date,
      strike,
      callStrike,
      putStrike,
    ],
    queryFn: () => {
      const p = new URLSearchParams({ mode: ctx.mode, name: ctx.name, interval: String(minutes) });
      if (ctx.date) p.set('date', ctx.date);
      if (ctx.expiry) p.set('expiry', ctx.expiry);
      if (strike) p.set('strike', strike);
      if (callStrike) p.set('callStrike', callStrike);
      if (putStrike) p.set('putStrike', putStrike);
      return oiGet<StraddleChart | null>('/market/options/straddle-chart', p.toString(), null);
    },
    enabled: satisfiable(ctx, true) && !!strike,
  });
}

/** INDIA VIX quote (the pinned index) for the chain header — polls on the live header cadence. */
export function useVix() {
  return useQuery({
    queryKey: ['market', 'vix'],
    queryFn: () => oiGet<VixQuote | null>('/market/vix', '', null),
    staleTime: 30_000,
    refetchInterval: 60_000,
  });
}

/** The faithful Options Chain feed (§20.7): live greeks/IV/OI/LTP/PCR + per-leg interval deltas. */
export function useChainTable() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'chain-table', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<ChainTable | null>('/market/options/chain-table', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}
