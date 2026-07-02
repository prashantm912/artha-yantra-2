import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { useChainTable, useTrendingOi } from '../../api/oiAnalytics.ts';
import { useSymbolContext } from '../../stores/symbolContext.store.ts';
import { foldTrending, isClosingBucket, type TrendingRow } from '../../api/trendingOiFold.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { StockOiWarmBar } from '../../components/StockOiWarmBar.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { EChart, type ChartTheme } from '../../components/atoms/EChart.tsx';
import { GoButton } from '../../components/atoms/GoButton.tsx';
import { LegMultiSelect } from '../../components/atoms/LegMultiSelect.tsx';
import { SignedCount } from '../../components/atoms/SignedCount.tsx';
import { SentimentBadge } from '../../components/atoms/SentimentBadge.tsx';
import { ValueDeltaCell } from '../../components/atoms/ValueDeltaCell.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { formatDecimal } from '../../lib/decimal.ts';
import { FIELD_HELP } from '../../core/fieldHelp.ts';

// OI Trending (oipulse §options/trending-oi): aggregated Call vs Put OI over the session with a
// derived directional sentiment. Time-ordered (newest on top), 100/page. All Δ/PCR/sentiment columns
// are FE-folded (foldTrending) from the per-bucket OI series; the column set + Diff-in-OI sign
// (puts−calls, positive = Bullish) follow the study doc. Wave-5 extras (audit §7): a strike-basket
// selector (server-side fold restriction), a positional (prev-day-EOD baseline) toggle, and the
// Graph view — ΔCall/ΔPut lines + the underlying price, the study's two-line read.

const TRENDING_INTERVALS = ['3m', '5m', '10m', '15m', '30m', '60m'] as const; // oipulse: no 1m

function BreakCell({ row }: { row: TrendingRow }) {
  const level = row.breakLevel ? `(${formatDecimal(row.breakLevel, 2)}) ` : '';
  if (row.dhBreak) return <span className="rounded bg-bull/15 px-1 text-xs text-bull">D.H.B {level}↑</span>;
  if (row.dlBreak) return <span className="rounded bg-bear/15 px-1 text-xs text-bear">D.L.B {level}↓</span>;
  return null; // blank cell (no break) — matches oipulse
}

function Direction({ dir }: { dir: number }) {
  if (dir === 0) return <span className="text-ay-muted">—</span>;
  const up = dir > 0;
  return (
    <span className={up ? 'text-bull' : 'text-bear'}>
      <span aria-hidden="true">{up ? '↑' : '↓'}</span>
      <span className="ay-sr-only">{up ? 'up' : 'down'}</span>
    </span>
  );
}

export function TrendingOiPage() {
  const [graph, setGraph] = useState(false);
  const [positional, setPositional] = useState(false);
  const [basket, setBasket] = useState<string[]>([]);
  const q = useTrendingOi(basket, positional ? 'peod' : null);
  const chainQ = useChainTable();
  const strikeOptions = useMemo(() => (chainQ.data?.rows ?? []).map((r) => r.strike), [chainQ.data]);
  const toggleStrike = (s: string) =>
    setBasket((prev) => (prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s]));
  const interval = useSymbolContext((s) => s.interval);
  // The fold is oldest-first; oipulse reads newest-on-top (the graph keeps chronological order).
  const chrono = useMemo(() => foldTrending(q.data?.items ?? []), [q.data]);
  const rows = useMemo(() => [...chrono].reverse(), [chrono]);

  // Graph view (study: "graph view renders two line panels (ΔCall-OI and ΔPut-OI over time)").
  const makeGraphOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      legend: { top: 0, textStyle: { color: t.muted }, data: ['Δ Call OI', 'Δ Put OI', 'Price'] },
      grid: { left: 64, right: 64, top: 32, bottom: 40 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
      },
      xAxis: {
        type: 'category',
        data: chrono.map((r) => r.bucket.slice(11, 16)),
        axisLine: { lineStyle: { color: t.border } },
        axisLabel: { color: t.muted },
      },
      yAxis: [
        {
          type: 'value',
          name: 'Δ OI',
          scale: true,
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted },
        },
        {
          type: 'value',
          name: 'Price',
          scale: true,
          splitLine: { show: false },
          axisLabel: { color: t.muted },
        },
      ],
      series: [
        { name: 'Δ Call OI', type: 'line', showSymbol: false, data: chrono.map((r) => r.chngCallOi), lineStyle: { color: t.bear, width: 1.75 }, itemStyle: { color: t.bear } },
        { name: 'Δ Put OI', type: 'line', showSymbol: false, data: chrono.map((r) => r.chngPutOi), lineStyle: { color: t.bull, width: 1.75 }, itemStyle: { color: t.bull } },
        { name: 'Price', type: 'line', showSymbol: false, yAxisIndex: 1, data: chrono.map((r) => (r.spot == null ? null : Number(r.spot))), lineStyle: { color: t.accent, width: 1.25, type: 'dashed' }, itemStyle: { color: t.accent } },
      ],
    }),
    [chrono],
  );

  const columns: DataColumn<TrendingRow>[] = [
    { id: 'date', header: 'Date', align: 'left', help: 'Calendar date of this interval bucket.', render: (r) => r.bucket.slice(0, 10) },
    { id: 'time', header: 'Time', align: 'left', help: 'Clock time at the end of this interval bucket; (EOD) marks the window closing at 15:30.', render: (r) => (isClosingBucket(r.bucket, interval) ? `${r.bucket.slice(11, 16)} (EOD)` : r.bucket.slice(11, 16)), mobileLabel: 'Time' },
    { id: 'ltp', header: 'LTP', help: FIELD_HELP.spot, render: (r) => (r.spot ? formatDecimal(r.spot, 2) : '—'), mobileLabel: 'LTP' },
    { id: 'break', header: 'Day H/L Break', align: 'center', help: "Flags when the underlying broke the day's high (D.H.B, bullish) or low (D.L.B, bearish) in this interval.", render: (r) => <BreakCell row={r} /> },
    { id: 'chngCall', header: 'Chng. In Call OI', help: 'Change in total call Open Interest versus the session-open baseline (added + / closed −).', render: (r) => <SignedCount value={r.chngCallOi} />, mobileLabel: 'Δ Call OI' },
    { id: 'chngPut', header: 'Chng. In Put OI', help: 'Change in total put Open Interest versus the session-open baseline (added + / closed −).', render: (r) => <SignedCount value={r.chngPutOi} />, mobileLabel: 'Δ Put OI' },
    { id: 'diff', header: 'Diff. in OI', help: 'ΔPut OI − ΔCall OI; positive leans bullish (puts being added faster than calls).', render: (r) => <SignedCount value={r.diffInOi} />, mobileLabel: 'Diff OI' },
    { id: 'direction', header: 'Direction of chng.', align: 'center', help: 'Whether the OI difference is pushing the bias up or down this interval.', render: (r) => <Direction dir={r.direction} /> },
    { id: 'chngDir', header: 'Chng. In Direction', help: 'How much the OI difference moved versus the prior interval.', render: (r) => <SignedCount value={r.chngInDirection} /> },
    { id: 'dirPct', header: 'Direction of chng. %', help: 'The direction change expressed as a percentage.', render: (r) => <ValueDeltaCell value={r.directionPct} suffix="%" /> },
    { id: 'pcr', header: 'Net PCR', help: FIELD_HELP.pcr, render: (r) => r.netPcr ?? '—', mobileLabel: 'PCR' },
    {
      id: 'sentiment',
      header: 'Sentiment',
      align: 'center',
      help: 'Derived directional read (bullish / bearish / neutral) from the OI shifts this interval.',
      render: (r) => <SentimentBadge label={r.sentiment.label} tone={r.sentiment.tone} />,
      mobileLabel: 'Sentiment',
    },
  ];

  return (
    <LoadBeat>
      <PageHeader title="OI Trending" help="Tracks total call versus put OI through the session, interval by interval, and reads a directional bias from which side is being built faster — newest reading on top." subtitle="Call vs Put OI over the session with a derived directional sentiment" />

      <StockOiWarmBar />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <FilterBar showName showExpiry showInterval allowedIntervals={TRENDING_INTERVALS} />
        <LegMultiSelect options={strikeOptions} selected={basket} onToggle={toggleStrike} />
        {basket.length > 0 && (
          <button
            type="button"
            onClick={() => setBasket([])}
            title="Clear the strike basket and fold across the whole chain again."
            className="h-9 rounded-md border border-ay-border px-2 text-sm text-ay-muted hover:border-accent"
          >
            Clear ({basket.length})
          </button>
        )}
        <label className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text" title="Rebase the Δ columns to the previous session's EOD (positional read) instead of today's open.">
          <input type="checkbox" checked={positional} onChange={(e) => setPositional(e.target.checked)} className="accent-accent" />
          Positional
        </label>
        <label className="flex h-9 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text" title="Chart the ΔCall-OI and ΔPut-OI lines with the underlying price instead of the table.">
          <input type="checkbox" checked={graph} onChange={(e) => setGraph(e.target.checked)} className="accent-accent" />
          Graph view
        </label>
        <GoButton onClick={() => q.refetch()} loading={q.isFetching} />
      </div>

      <p className="mb-3 text-xs text-ay-muted">
        Diff. in OI = ΔPut OI − ΔCall OI (puts−calls); positive = Bullish. Δ columns are cumulative vs{' '}
        {positional ? "the PREVIOUS session's EOD (positional)" : 'the session-open baseline'} and
        computed across {basket.length > 0 ? `the ${basket.length}-strike basket` : 'the FULL chain'}
        {basket.length === 0 &&
          " — oipulse's table uses a ~15-strike ATM basket, so magnitudes differ while the direction agrees"}
        .
      </p>

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{ title: 'No trending data — pick an underlying + expiry with captured snapshots.' }}
        errorTitle="Couldn't load trending OI"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={12} />}
      >
        {() =>
          graph ? (
            <BeatBlock className="card shadow-e1">
              <EChart
                makeOption={makeGraphOption}
                className="h-64 sm:h-80 lg:h-[440px]"
                ariaLabel="Cumulative change in call and put OI over the session with the underlying price"
              />
            </BeatBlock>
          ) : (
            <BeatBlock>
              <DataTable
                columns={columns}
                rows={rows}
                rowKey={(r) => r.bucket}
                pageSize={100}
                ariaLabel="OI trending per interval"
                emptyMessage="No trending data — pick an underlying + expiry with captured snapshots."
              />
            </BeatBlock>
          )
        }
      </QueryState>
    </LoadBeat>
  );
}
