import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { formatDecimal } from '../../lib/decimal.ts';
import { nearestStrike } from '../../lib/strikes.ts';
import {
  useChainTable,
  useConnectingDots,
  useOiHeatmap,
  useStraddleChart,
} from '../../api/oiAnalytics.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { Metric } from '../../components/atoms/Metric.tsx';
import { SentimentBadge, type SentimentTone } from '../../components/atoms/SentimentBadge.tsx';
import { OptionsChainTable } from '../../components/OptionsChainTable.tsx';
import { ConnectingDotsTable } from '../../components/ConnectingDotsTable.tsx';
import { StraddleChart } from '../../components/StraddleChart.tsx';
import { CallOiHeatmap, PutOiHeatmap } from '../../components/OiHeatmapChart.tsx';
import { trendMeta } from '../../core/connectingDots.ts';
import { PageHeader, LiveDot } from '../../components/PageHeader.tsx';
import { isMarketHoursIst } from '../../lib/marketHours.ts';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { SignalsFeedPanel } from '../signals/SignalsFeedPanel.tsx';
import { PaperBookPanel } from './PaperBookPanel.tsx';
import { PanelError } from '../../components/atoms/PanelError.tsx';

// Scalping Cockpit (master plan §20 — Cockpit composite): ONE live operator screen that composes the
// highest-signal scalping views (option chain · OI-confluence/Connecting-Dots matrix + sentiment ·
// straddle premium · live signals · OI heatmap) so the owner trades from one screen. A single shared
// underlying+expiry+interval FilterBar drives EVERY panel: each panel reuses the same oipulse data
// hook, all of which read the shared SymbolContext store, so one Go fans out to all of them. Read-only,
// additive — the standalone pages keep working untouched. The whole page is route-lazy in App.tsx, so
// the ECharts bundle (straddle + heatmap) rides the cockpit chunk, not the main payload.
// Revamp rollout (Trading screens): the sr-only h1 becomes the visible signature PageHeader (text
// preserved). The dense multi-panel live layout is deliberately NOT restructured — each panel keeps
// its bespoke live-state copy + the shared FilterBar fan-out; only the page title lockup changes.

/** A titled cockpit panel card. The optional `to` link deep-links to the panel's full standalone page. */
function Panel({
  title,
  to,
  linkLabel,
  children,
}: {
  title: string;
  to?: string;
  linkLabel?: string;
  children: ReactNode;
}) {
  return (
    <section className="min-w-0 rounded-lg border border-ay-border bg-surface-1 p-3">
      <div className="mb-2 flex items-center justify-between gap-2">
        <h2 className="text-h3 text-ay-text">{title}</h2>
        {to && (
          <Link to={to} className="whitespace-nowrap text-xs text-accent hover:underline">
            {linkLabel ?? 'Open'} →
          </Link>
        )}
      </div>
      {children}
    </section>
  );
}

/** The composite OI-confluence read for the matrix — the newest interval's 5-state Trend, as a badge. */
function matrixSentiment(trend: number | undefined): { label: string; tone: SentimentTone } | null {
  if (trend == null) return null;
  const m = trendMeta(trend);
  const tone: SentimentTone = m.label.includes('Bullish')
    ? 'bull'
    : m.label.includes('Bearish')
      ? 'bear'
      : 'neutral';
  return { label: m.label, tone };
}

export function CockpitPage() {
  // The paper panel's selected book — mirrored here so the "Full ledger" deep link lands on the SAME
  // book ('all' has no /paper counterpart, so the aggregate keeps the bare /paper landing).
  const [paperBook, setPaperBook] = useState<string>('scalper');
  // (a) live option chain — also the source of the ATM strike the straddle panel defaults to.
  const chainQ = useChainTable();
  const chain = chainQ.data ?? null;
  const chainRows = useMemo(() => chain?.rows ?? [], [chain]);
  const strikes = useMemo(() => chainRows.map((r) => r.strike), [chainRows]);
  const atm = useMemo(() => nearestStrike(strikes, chain?.spot ?? null), [strikes, chain]);

  // (b) Connecting-Dots / OI-confluence matrix + the derived sentiment badge.
  const cdQ = useConnectingDots();
  const cdRows = useMemo(() => cdQ.data?.rows ?? [], [cdQ.data]);
  const sentiment = matrixSentiment(cdRows[0]?.trend);

  // (c) straddle premium mini-chart — pinned to the ATM strike (re-defaults when the chain moves).
  const [strike, setStrike] = useState<string | null>(null);
  useEffect(() => {
    if (atm && (strike == null || !strikes.includes(strike))) setStrike(atm);
  }, [atm, strike, strikes]);
  const straddleQ = useStraddleChart(strike, null, null, 3);
  const straddle = straddleQ.data ?? null;

  // (e) compact OI heatmap (CE + PE ΔOI grid).
  const heatQ = useOiHeatmap();
  const heat = heatQ.data ?? null;
  const hasGrid = !!heat && heat.buckets.length > 0 && heat.strikes.length > 0;

  // One Go fans out a refetch to every panel (each is already reactive to the shared context).
  const refetchAll = () => {
    chainQ.refetch();
    cdQ.refetch();
    straddleQ.refetch();
    heatQ.refetch();
  };
  const fetching =
    chainQ.isFetching || cdQ.isFetching || straddleQ.isFetching || heatQ.isFetching;

  // Audit 2026-07-02 §6 / §11 item 8: the market panels used to freeze at the last Go with no
  // staleness cue while the paper book polled live. Auto-refresh every 45s during IST market hours
  // (paused when the tab is hidden); the header LiveDot renders the chain asOf + stale flag.
  useEffect(() => {
    const id = window.setInterval(() => {
      if (document.visibilityState !== 'visible' || !isMarketHoursIst()) return;
      refetchAll();
    }, 45_000);
    return () => window.clearInterval(id);
    // refetchAll is re-created per render but only closes over stable query handles.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <LoadBeat>
      <PageHeader
        title="Scalping cockpit"
        subtitle="One live operator screen — option chain · OI confluence · straddle · signals · paper book · heatmap"
        help="One live screen that fans out a single underlying/expiry/interval selection across every scalping view — option chain, OI-confluence matrix, straddle premium, signals, paper book and OI heatmap; pick a symbol and press Go to refresh them all. Market panels auto-refresh every 45s during market hours."
        right={<LiveDot stale={!!chain?.stale} detail={chain?.asOf ? chain.asOf.slice(11, 19) : undefined} />}
      />

      {/* The single shared control bar — drives every panel below via the SymbolContext store. */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval />
        <GoButton onClick={refetchAll} loading={fetching} title="Refresh every panel for the selected underlying, expiry and interval" />
      </div>

      {/* Live header strip (spot / PCR / ATM / sentiment). */}
      <div
        className="card shadow-e1 mb-3 flex flex-wrap items-center gap-2"
        aria-live="polite"
      >
        <Metric
          label={chain?.underlying ?? 'Underlying'}
          value={chain?.spot ? formatDecimal(chain.spot, 2) : '—'}
          title="Live spot price of the selected underlying index or stock"
        />
        <Metric label="Total PCR" value={chain?.pcr ? formatDecimal(chain.pcr, 2) : '—'} title="Put-Call Ratio — total put OI ÷ total call OI; above 1 leans bearish positioning, below 1 leans bullish" />
        <Metric label="ATM" value={atm ?? '—'} title="At-the-money strike — the strike closest to the current spot price" />
        <span className="flex items-center gap-1.5 text-xs text-ay-muted">
          Sentiment
          {sentiment ? (
            <SentimentBadge label={sentiment.label} tone={sentiment.tone} />
          ) : (
            <span className="text-ay-text">—</span>
          )}
        </span>
      </div>

      {/* Adaptive grid: single scrollable column on narrow viewports, two columns from xl. */}
      <BeatBlock className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        {/* (a) Option chain (compact) */}
        <Panel title="Option chain" to="/options/options-chain" linkLabel="Full chain">
          {chainQ.isError ? (
            <PanelError onRetry={() => void chainQ.refetch()} />
          ) : chain == null && !chainQ.isLoading ? (
            <p className="p-1 text-sm text-ay-muted">
              No chain — pick an underlying + expiry with a live option chain.
            </p>
          ) : (
            <div className="max-h-[26rem] overflow-auto" tabIndex={0} role="region" aria-label="Options chain panel">
              <OptionsChainTable
                rows={chainRows}
                spot={chain?.spot ?? null}
                atmStrike={atm}
                optionalKeys={[]}
              />
            </div>
          )}
        </Panel>

        {/* (b) Connecting-Dots / OI-confluence matrix + sentiment */}
        <Panel title="OI confluence matrix" to="/features/connecting-dots" linkLabel="Connecting Dots">
          {cdQ.isError && <PanelError onRetry={() => void cdQ.refetch()} />}
          {!cdQ.isError && cdQ.data == null && !cdQ.isLoading && (
            <p className="mb-2 p-1 text-sm text-ay-muted">
              No matrix — pick an index + interval with an active (or captured) session.
            </p>
          )}
          <div className="max-h-[26rem] overflow-auto" tabIndex={0} role="region" aria-label="OI confluence panel">
            <ConnectingDotsTable rows={cdRows} />
          </div>
        </Panel>

        {/* (c) Straddle premium mini-chart */}
        <Panel title="Straddle premium" to="/options/straddle-chart" linkLabel="Straddle chart">
          <div className="mb-2 text-xs text-ay-muted">
            ATM straddle {strike ?? '—'} · 3 min
          </div>
          {straddle != null && straddle.items.length > 0 ? (
            <StraddleChart
              items={straddle.items}
              callStrike={straddle.callStrike}
              putStrike={straddle.putStrike}
              underlying={straddle.underlying}
            />
          ) : straddleQ.isError ? (
            <PanelError onRetry={() => void straddleQ.refetch()} />
          ) : (
            <p className="p-1 text-sm text-ay-muted">
              {straddleQ.isLoading
                ? 'Loading…'
                : 'No straddle candles — pick a strike with intraday option trades.'}
            </p>
          )}
        </Panel>

        {/* (d) Live signals feed — with the per-signal "Take (paper)" action (paper-trading console). */}
        <Panel title="Live signals" to="/signals" linkLabel="All signals">
          <SignalsFeedPanel status="ACTIVE" takeable />
        </Panel>

        {/* Paper book — live positions / P&L / exposure + the per-book risk-limit guard (full width).
            The per-signal "Take (paper)" action above opens a defined-risk paper trade into that
            signal's OWN book (book = the signal's first tag); the panel's book selector picks which
            book (or the all-books aggregate) to watch here. */}
        <div className="xl:col-span-2">
          <Panel
            title="Paper book"
            to={paperBook === 'all' ? '/paper' : `/paper/${paperBook}`}
            linkLabel="Full ledger"
          >
            <PaperBookPanel onBookChange={setPaperBook} />
          </Panel>
        </div>

        {/* (e) Compact OI heatmap — spans the full width on the two-column layout. */}
        <div className="xl:col-span-2">
          <Panel title="OI change heatmap" to="/options/oi-heatmap" linkLabel="Full heatmap">
            {heatQ.isError ? (
              <PanelError onRetry={() => void heatQ.refetch()} />
            ) : !hasGrid && !heatQ.isLoading ? (
              <p className="p-1 text-sm text-ay-muted">
                No OI-change grid — pick an index + expiry with captured chain snapshots.
              </p>
            ) : (
              hasGrid && (
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                  <div>
                    <h3 className="mb-1 text-center text-xs font-semibold text-ay-text">
                      Call (CE) OI Change
                    </h3>
                    <CallOiHeatmap data={heat!} />
                  </div>
                  <div>
                    <h3 className="mb-1 text-center text-xs font-semibold text-ay-text">
                      Put (PE) OI Change
                    </h3>
                    <PutOiHeatmap data={heat!} />
                  </div>
                </div>
              )
            )}
          </Panel>
        </div>
      </BeatBlock>
    </LoadBeat>
  );
}
