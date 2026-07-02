import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, listItems } from './client.ts';
import { useSymbolContext } from '../stores/symbolContext.store.ts';
import type {
  ActiveStrikes,
  BanksAnalysis,
  Breadth,
  ChainTable,
  ConnectingDots,
  EquityDelivery,
  EquityReturns,
  FiiDerivativeRow,
  FiiDiiRow,
  FutEodRow,
  FutOiChart,
  FutSeriesPoint,
  FutSpurtChain,
  IntervalWiseOi,
  LongShortRow,
  Movers,
  MultiOi,
  EquityNews,
  IndexContribution,
  OiBuzzHeatmap,
  OiExpiryStrike,
  OiHeatmap,
  OpenHighLow,
  OpenHighStrategyStrike,
  OiStats,
  SectorHeatmap,
  SectorStats,
  OiStrikePoint,
  OptOiChart,
  ParticipantOiRow,
  PcrSeriesPoint,
  PremiumChain,
  SpurtChain,
  StraddleChart,
  CalendarSpreadChart,
  StrikeSeries,
  TrendSeries,
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
  const mode = useSymbolContext((s) => s.mode);
  const date = useSymbolContext((s) => s.date);
  return {
    name: useSymbolContext((s) => s.name),
    expiry: useSymbolContext((s) => s.expiry),
    interval: useSymbolContext((s) => s.interval),
    mode,
    // Live NEVER carries a date (audit 2026-07-02 §9.3, T4): the store keeps the picked date for a
    // return to History, but letting it leak into a Live request served YESTERDAY's rows under a
    // "Live" label. Normalizing here fixes every hook's params AND query key together.
    date: mode === 'history' ? date : null,
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

/**
 * GET that maps 422 DATA_GAP → the empty shape (caught, so no throw → no toast). ONLY that pair
 * is absorbed (audit P1-11): the old blanket silenceToast swallowed 5xx/network failures too, so
 * a crashed market-data rendered as calm "no data" on the live cockpit — a real outage was
 * indistinguishable from a quiet session. Everything else now rethrows UN-silenced, feeding the
 * global toast and each page's QueryState error card.
 */
async function oiGet<T>(path: string, params: string, emptyOn422: T): Promise<T> {
  try {
    return await apiFetch<T>(`${path}?${params}`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 422 && err.code === 'DATA_GAP') {
      return emptyOn422;
    }
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

/**
 * Active strikes (§options/active-strikes). With `buckets` the response also carries the two per-bucket
 * series (sentiment % + active-strike Call/Put OI) for the page's two charts — one DB read, one call.
 */
export function useActiveStrikes(buckets?: number) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'active-strikes', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date, buckets ?? null],
    queryFn: () => {
      const params = buckets ? `${oiParams(ctx, true)}&buckets=${buckets}` : oiParams(ctx, true);
      return oiGet<ActiveStrikes | null>('/market/options/active-strikes', params, null);
    },
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

/**
 * OI Spurt feed. {@code window='cumulative'} (the OI Spurt page — matches oipulse: ΔOI vs the session
 * OPEN) vs the default per-interval ΔOI (Big OI page keeps this). Absent → server default (interval).
 */
export function useOptionsSpurt(window?: 'cumulative' | 'interval') {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'spurt', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date, window ?? null],
    queryFn: () => {
      const params = window ? `${oiParams(ctx, true)}&window=${window}` : oiParams(ctx, true);
      return oiGet<SpurtChain | null>('/market/options/spurt', params, null);
    },
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
 * session. The interval is RAW MINUTES owned by the page (the oipulse set is 1/3/5/10/15/30/60 —
 * wider than the shared OiInterval, which lacks 10m). The BE requires a base {@code strike}; a
 * strangle adds call/put overrides. Name/expiry/mode/date still ride the shared control bar.
 */
export function useStraddleChart(
  strike: string | null,
  callStrike: string | null,
  putStrike: string | null,
  intervalMinutes: number,
) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: [
      'oi',
      'straddle',
      ctx.name,
      ctx.expiry,
      intervalMinutes,
      ctx.mode,
      ctx.date,
      strike,
      callStrike,
      putStrike,
    ],
    queryFn: () => {
      const p = new URLSearchParams({
        mode: ctx.mode,
        name: ctx.name,
        interval: String(intervalMinutes),
      });
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

/**
 * Calendar Spread (study {@code strategies/calendar-spread}): the (near − far) premium-differential
 * candles for one strike + option type across two expiries. {@code intervalMinutes} is RAW MINUTES
 * owned by the page (1/3/5/10/15/30/60). The two expiries are page-owned (not the shared control bar's
 * single expiry). Needs a strike + both expiries; name/mode/date ride the shared control bar.
 */
export function useCalendarSpreadChart(
  strike: string | null,
  optionType: string,
  nearExpiry: string | null,
  farExpiry: string | null,
  intervalMinutes: number,
) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: [
      'oi',
      'calendar-spread',
      ctx.name,
      ctx.mode,
      ctx.date,
      strike,
      optionType,
      nearExpiry,
      farExpiry,
      intervalMinutes,
    ],
    queryFn: () => {
      const p = new URLSearchParams({
        mode: ctx.mode,
        name: ctx.name,
        optionType,
        interval: String(intervalMinutes),
      });
      if (ctx.date) p.set('date', ctx.date);
      if (strike) p.set('strike', strike);
      if (nearExpiry) p.set('nearExpiry', nearExpiry);
      if (farExpiry) p.set('farExpiry', farExpiry);
      return oiGet<CalendarSpreadChart | null>(
        '/market/options/calendar-spread',
        p.toString(),
        null,
      );
    },
    enabled: satisfiable(ctx, false) && !!strike && !!nearExpiry && !!farExpiry,
  });
}

/**
 * Options Chart (§options/options-chart): one strike's CE + PE premium candle + OI/IV series (one fetch,
 * both legs). {@code intervalMinutes} is RAW MINUTES owned by the page (1/3/5/10/15/30/60). Needs a strike.
 */
export function useOptionsChart(strike: string | null, intervalMinutes: number) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'options-chart', ctx.name, ctx.expiry, intervalMinutes, ctx.mode, ctx.date, strike],
    queryFn: () => {
      const p = new URLSearchParams({
        mode: ctx.mode,
        name: ctx.name,
        interval: String(intervalMinutes),
      });
      if (ctx.date) p.set('date', ctx.date);
      if (ctx.expiry) p.set('expiry', ctx.expiry);
      if (strike) p.set('strike', strike);
      return oiGet<OptOiChart | null>('/market/options/options-chart', p.toString(), null);
    },
    enabled: satisfiable(ctx, true) && !!strike,
  });
}

/**
 * Multiple OI Chart (§options/multiple-oi-chart): overlay several selected legs' OI lines + the underlying
 * price. {@code legs} are "57200 CE" labels sent as REPEATED ?leg= params; {@code intervalMinutes} is RAW
 * MINUTES owned by the page. Disabled until ≥1 leg is selected (the BE 400s on an empty leg list).
 */
export function useMultiLegOiChart(legs: string[], intervalMinutes: number) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'multiple-oi', ctx.name, ctx.expiry, intervalMinutes, ctx.mode, ctx.date, legs.join(',')],
    queryFn: () => {
      const p = new URLSearchParams({
        mode: ctx.mode,
        name: ctx.name,
        // OiInterval TOKEN form ("3m") — this endpoint reads reader.series() and so is OiInterval-bound
        // (via OiQuery.of/OiInterval.parse), unlike the candle charts whose BE takes a raw int interval.
        interval: `${intervalMinutes}m`,
      });
      if (ctx.date) p.set('date', ctx.date);
      if (ctx.expiry) p.set('expiry', ctx.expiry);
      legs.forEach((l) => p.append('leg', l));
      return oiGet<MultiOi | null>('/market/options/multiple-oi', p.toString(), null);
    },
    enabled: satisfiable(ctx, true) && legs.length > 0,
  });
}

/** Connecting Dots multi-factor matrix (§20.7.8): per-interval rows for the chosen index (no expiry). */
export function useConnectingDots() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'connecting-dots', ctx.name, ctx.interval, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<ConnectingDots | null>('/market/connecting-dots', oiParams(ctx, false), null),
    enabled: satisfiable(ctx, false),
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

// ── Wave-2 depth hooks (§20.3). Symbol-context driven (name/expiry/interval/mode/date from the bar).

/** OI Trending (§20.3): per-bucket total/CE/PE OI + spot + UP/DOWN/FLAT over the last N buckets. */
export function useTrendingOi() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'trending', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () => oiGet<TrendSeries | null>('/market/options/trending', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

/** OI-change heatmap (§20 breadth): strike × time grid of interval ΔOI (CE+PE) over the session. */
export function useOiHeatmap() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'oi-heatmap', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () => oiGet<OiHeatmap | null>('/market/options/oi-heatmap', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

/**
 * OI Expiry Strategy / "Options EOD OI Analysis" (oipulse §options/oi-expiry-strategy): per-strike
 * (CE+PE) last-N-session EOD OHLC + OI + Volume tables, windowed to the ATM strikes. The `{items}`
 * envelope carries one entry per strike; 422 DATA_GAP → empty (the page renders its empty state).
 */
export function useOiExpiry() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'oi-expiry', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: async () => {
      const res = await oiGet<{ items?: OiExpiryStrike[] }>(
        '/market/options/oi-expiry',
        oiParams(ctx, true),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: satisfiable(ctx, true),
  });
}

/**
 * Open & High Strategy (oipulse §strategies/open-high-strategy, Siva #2): per-strike (CE+PE)
 * Open=High / Open=Low premium-reversion scan with the historical trigger probability, windowed to
 * the ATM strikes. The `{items}` envelope carries one entry per strike; 422 DATA_GAP → empty.
 */
export function useOpenHighStrategy() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'open-high-strategy', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: async () => {
      const res = await oiGet<{ items?: OpenHighStrategyStrike[] }>(
        '/market/options/open-high-strategy',
        oiParams(ctx, true),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: satisfiable(ctx, true),
  });
}

/** Top OI gainer/loser strikes across the 15m/60m/daily lookbacks (oipulse Interval-wise OI). */
export function useIntervalWiseOi() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'interval-wise', ctx.name, ctx.expiry, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<IntervalWiseOi | null>('/market/options/interval-wise-oi', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

/** Intraday PCR-vs-price (the OI-Statistics chart) — source-aware (native fold or Upstox full-chain). */
export function usePcrSeries() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'pcr-series', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () => oiGet<PcrSeriesPoint[]>('/market/options/pcr-series', oiParams(ctx, true), []),
    enabled: satisfiable(ctx, true),
  });
}

/** Options Premium (§20.3): per-strike straddle premium + the ATM straddle for the latest bucket. */
export function useOptionsPremium() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['oi', 'premium', ctx.name, ctx.expiry, ctx.interval, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<PremiumChain | null>('/market/options/premium', oiParams(ctx, true), null),
    enabled: satisfiable(ctx, true),
  });
}

/** Futures OI Spurt (§20.3): per-contract interval buildup (no expiry — keyed on the index name). */
export function useFuturesSpurt() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['fut', 'spurt', ctx.name, ctx.interval, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<FutSpurtChain | null>('/market/futures/spurt', oiParams(ctx, false), null),
    enabled: satisfiable(ctx, false),
  });
}

/**
 * Futures OI Analysis series (§futures/oi-analysis): one contract's raw per-bucket points over the
 * session (no expiry — keyed on the index name; the BE picks the active front). The FE folds the
 * per-interval table columns. Returns the `{items, interval, asOf}` envelope (interval drives the label).
 */
export function useFuturesOiSeries() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['fut', 'oi-series', ctx.name, ctx.interval, ctx.mode, ctx.date],
    queryFn: () =>
      oiGet<{ items?: FutSeriesPoint[]; interval?: string; asOf?: string }>(
        '/market/futures/oi-analysis-series',
        oiParams(ctx, false),
        { items: [] },
      ),
    enabled: satisfiable(ctx, false),
  });
}

/**
 * Futures OI Chart (§futures/oi-chart): one contract's candle (price) + OI series for the dual-axis
 * combo. {@code intervalMinutes} is RAW MINUTES owned by the page (the oipulse set 1/3/5/10/15/30/60 is
 * wider than the shared OiInterval — the candles aggregate from 1m base). Keyed on the index name; the
 * BE picks the active front contract (no expiry sent). Null → empty state on a 422.
 */
export function useFuturesOiChart(intervalMinutes: number) {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['fut', 'oi-chart', ctx.name, intervalMinutes, ctx.mode, ctx.date],
    queryFn: () => {
      const p = new URLSearchParams({
        mode: ctx.mode,
        name: ctx.name,
        interval: String(intervalMinutes),
      });
      if (ctx.date) p.set('date', ctx.date);
      return oiGet<FutOiChart | null>('/market/futures/oi-chart', p.toString(), null);
    },
    enabled: satisfiable(ctx, false),
  });
}

/**
 * Banks Analysis (§futures/banks-analysis): the sector-wide time × bank OI matrix. NAME-FREE + expiry-free
 * (the 6 banks are config-fixed) — so it cannot reuse oiParams/satisfiable (both inject/require a name).
 * Only mode/date/interval ride; the only enable guard is "history needs a date".
 */
export function useBanksAnalysis() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['fut', 'banks-analysis', ctx.interval, ctx.mode, ctx.date],
    queryFn: () => {
      const p = new URLSearchParams({ mode: ctx.mode, interval: ctx.interval });
      if (ctx.date) p.set('date', ctx.date);
      return oiGet<BanksAnalysis | null>('/market/futures/banks-analysis', p.toString(), null);
    },
    enabled: !(ctx.mode === 'history' && !ctx.date),
  });
}

/** Futures Market Movers (§20.3): gainers/losers by day price% (no expiry — keyed on the index name). */
export function useFuturesMovers() {
  const ctx = useOiCtx();
  return useQuery({
    queryKey: ['fut', 'movers', ctx.name, ctx.interval, ctx.mode, ctx.date],
    queryFn: () => oiGet<Movers | null>('/market/futures/movers', oiParams(ctx, false), null),
    enabled: satisfiable(ctx, false),
  });
}

// ── Wave-2 date-range hooks. EOD / FII-DII are date-windowed, NOT symbol-context driven.

function rangeParams(from: string, to: string, name?: string): string {
  const p = new URLSearchParams({ from, to });
  if (name) p.set('name', name);
  return p.toString();
}

/** Futures EOD OI Analyzer (§20.3): per-contract daily OHLC + OI rollup over [from, to] for one index. */
export function useFuturesEod(name: string, from: string, to: string) {
  return useQuery({
    queryKey: ['fut', 'eod', name, from, to],
    queryFn: async () => {
      const res = await oiGet<{ items?: FutEodRow[] }>(
        '/market/futures/eod',
        rangeParams(from, to, name),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: !!name && !!from,
  });
}

/** FII/DII Capital Market (§20.3): FII + DII cash buy/sell/net rows over [from, to]. */
export function useFiiDiiCash(from: string, to: string) {
  return useQuery({
    queryKey: ['fiidii', 'cash', from, to],
    queryFn: async () => {
      const res = await oiGet<{ items?: FiiDiiRow[] }>(
        '/market/fii-dii/cash',
        rangeParams(from, to),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: !!from,
  });
}

/** FII net activity across the four F&O derivative segments (oipulse FII Derivative Stats; Upstox-sourced). */
export function useFiiDerivativeStats(from: string, to: string) {
  return useQuery({
    queryKey: ['fiidii', 'derivative-stats', from, to],
    queryFn: async () => {
      const res = await oiGet<{ items?: FiiDerivativeRow[] }>(
        '/market/fii-dii/derivative-stats',
        rangeParams(from, to),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: !!from,
  });
}

/** Participant-wise OI (§20.3): FII/DII/Pro/Client long-short contracts over [from, to]. */
export function useParticipantOi(from: string, to: string) {
  return useQuery({
    queryKey: ['fiidii', 'participant-oi', from, to],
    queryFn: async () => {
      const res = await oiGet<{ items?: ParticipantOiRow[] }>(
        '/market/fii-dii/participant-oi',
        rangeParams(from, to),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: !!from,
  });
}

/** Per-stock news / announcements (Upstox /v2/news). available=false when the source isn't live. */
export function useEquityNews(symbol: string | null) {
  return useQuery({
    queryKey: ['equity', 'news', symbol],
    queryFn: () =>
      oiGet<EquityNews | null>(
        '/market/equity/news',
        new URLSearchParams({ symbol: symbol ?? '' }).toString(),
        null,
      ),
    enabled: !!symbol,
  });
}

/** Open=High / Open=Low (oipulse): an index's constituents that opened at their day high / low. */
export function useOpenHighLow(index: string | null) {
  return useQuery({
    queryKey: ['equity', 'open-high-low', index],
    queryFn: () =>
      oiGet<OpenHighLow | null>(
        '/market/equity/open-high-low',
        new URLSearchParams({ name: index ?? '' }).toString(),
        null,
      ),
    enabled: !!index,
  });
}

/** Index Contribution (oipulse): per-constituent weighted contribution, split advances/declines. */
export function useIndexContribution(index: string | null) {
  return useQuery({
    queryKey: ['equity', 'index-contribution', index],
    queryFn: () =>
      oiGet<IndexContribution | null>(
        '/market/equity/index-contribution',
        new URLSearchParams({ name: index ?? '' }).toString(),
        null,
      ),
    enabled: !!index,
  });
}

/** Sector Heatmap (oipulse): an index's constituents, grouped by sector client-side. */
export function useSectorHeatmap(index: string | null) {
  return useQuery({
    queryKey: ['equity', 'sector-heatmap', index],
    queryFn: () =>
      oiGet<SectorHeatmap | null>(
        '/market/equity/sector-heatmap',
        new URLSearchParams({ name: index ?? '' }).toString(),
        null,
      ),
    enabled: !!index,
  });
}

/** Sector Stats (oipulse): per-sector roll-up cards + the per-stock factor table (no params). */
export function useSectorStats() {
  return useQuery({
    queryKey: ['equity', 'sector-stats'],
    queryFn: () => oiGet<SectorStats | null>('/market/equity/sector-stats', '', null),
    staleTime: 60_000,
  });
}

/** Equity Returns (oipulse): multi-timeframe returns screener over the EQ universe (no params). */
export function useEquityReturns() {
  return useQuery({
    queryKey: ['equity', 'returns'],
    queryFn: () => oiGet<EquityReturns | null>('/market/equity/returns', '', null),
    staleTime: 60_000,
  });
}

/** Equity Delivery Data (oipulse): one stock's daily delivery series over the most recent N sessions.
 * 422 DATA_GAP (symbol has no EQ bhavcopy) → null → the page renders its empty state. */
export function useEquityDelivery(symbol: string | null, days: number) {
  return useQuery({
    queryKey: ['equity', 'delivery', symbol, days],
    queryFn: () =>
      oiGet<EquityDelivery | null>(
        '/market/equity/delivery',
        new URLSearchParams({ symbol: symbol ?? '', days: String(days) }).toString(),
        null,
      ),
    enabled: !!symbol,
  });
}

/** Futures OI Buzz index selector options (the seeded static-constituent reference keys). */
export function useOiBuzzIndices() {
  return useQuery({
    queryKey: ['oi-buzz', 'indices'],
    queryFn: async () => {
      const res = await apiFetch<{ items?: string[] }>('/market/futures/oi-buzz-indices');
      return res.items ?? [];
    },
    staleTime: 60 * 60 * 1000, // static reference — refetch hourly at most
  });
}

/** Futures OI Buzz heatmap (oipulse "Futures Heatmap"): constituent %change tiles + advance/decline.
 * 422 DATA_GAP (no monthly futures resolved) → null → the page renders its empty state. */
export function useOiBuzz(index: string | null, date?: string | null) {
  return useQuery({
    queryKey: ['oi-buzz', 'heatmap', index, date ?? null],
    queryFn: () => {
      const p = new URLSearchParams({ name: index ?? '' });
      if (date) p.set('date', date); // a past date → the historical spot-1d %change map
      return oiGet<OiBuzzHeatmap | null>('/market/futures/oi-buzz-heatmap', p.toString(), null);
    },
    enabled: !!index,
  });
}

/** Market breadth for one date (oipulse Equity → Breadth): advance/decline + delivery-% leaders from
 * the EQ bhavcopy. 422 DATA_GAP (no bhavcopy for that date) → null → the page renders its empty state. */
export function useBreadth(date: string | null) {
  return useQuery({
    queryKey: ['breadth', date],
    queryFn: () =>
      oiGet<Breadth | null>(
        '/market/breadth',
        new URLSearchParams({ date: date ?? '' }).toString(),
        null,
      ),
    enabled: !!date,
  });
}

/** FII Long-Short Ratio (§20.3): FII index-futures long/short contracts + the ratio over [from, to]. */
export function useFiiLongShort(from: string, to: string) {
  return useQuery({
    queryKey: ['fiidii', 'long-short', from, to],
    queryFn: async () => {
      const res = await oiGet<{ items?: LongShortRow[] }>(
        '/market/fii-dii/long-short',
        rangeParams(from, to),
        { items: [] },
      );
      return listItems(res);
    },
    enabled: !!from,
  });
}
