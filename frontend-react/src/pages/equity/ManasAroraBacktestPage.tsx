import { useState } from 'react';
import { Link } from 'react-router-dom';
import { formatDecimal } from '../../lib/decimal.ts';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import {
  useManasBacktestCompare,
  useRunManasBacktest,
  type ManasReport,
  type ManasSetupStat,
} from '../../api/manasArora.ts';

// /equity/manas-arora/backtest — the deep-history swing-backtest comparison. GETs the latest
// multi-variant result (technical / rs / turnover / pyramid A/B side by side) and renders each
// variant's per-setup trade stats + its RS-priority portfolio summary. A "Run backtest" button kicks
// off a fresh run (~minutes, background thread). Lean by design — tables, not charts.

/** The variant the plain funnel analogue reports (the full-filter, non-pyramid run — pyramid disarmed per H4). */
const PRIMARY_VARIANT = 'rs-turnover-nopyramid';

export function ManasAroraBacktestPage() {
  const compare = useManasBacktestCompare();
  const run = useRunManasBacktest();
  const [ran, setRan] = useState(false);

  const trigger = () =>
    run.mutate(undefined, {
      onSuccess: () => {
        setRan(true);
        compare.refetch();
      },
    });

  const data = compare.data;
  const running = data?.status === 'running' || run.isPending;

  return (
    <LoadBeat>
      <PageHeader
        title="Manas Arora swing backtest"
        subtitle="Deep-history (~11y candles@1d) multi-variant comparison — technical / RS / turnover / pyramiding"
        help="A faithful event-driven replay of the two setups (breakout + vcp pivot entry, ATR-stop / ATR-trail / fast-move / parabolic exit, pyramiding) over ~11 years of daily NSE-EQ bars. The variant grid isolates whether the weekly cross-sectional RS-rank gate, the ₹/day turnover floor, and add-to-winner each sharpen the edge. Compute is minutes, so Run kicks it off on a background thread; the table shows the latest completed result."
      />
      <Link to="/equity/manas-arora" className="mb-3 inline-block text-sm text-accent hover:underline">
        ← Back to screener
      </Link>

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={trigger}
          disabled={running}
          title="Kick off a fresh multi-variant swing backtest (runs on a background thread; ~minutes)."
          className="h-9 rounded-md bg-accent px-3 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
        >
          {running ? 'Running…' : 'Run backtest'}
        </button>
        {data && (
          <span className="text-xs text-ay-muted">
            {data.status}
            {data.fromDate ? ` · from ${data.fromDate}` : ''}
            {data.runAt ? ` · ran ${data.runAt.slice(0, 10)}` : ''}
            {data.variants.length ? ` · ${data.variants.length} variants` : ''}
          </span>
        )}
        {ran && running && (
          <span className="text-xs text-ay-muted">
            Backtest triggered — re-open this page in a few minutes for the result.
          </span>
        )}
      </div>

      <QueryState
        query={compare}
        empty={{ title: 'No backtest run yet — click Run backtest to compute the comparison.' }}
        errorTitle="Couldn't load the backtest"
        skeleton={<Skeleton variant="table-rows" rows={8} cols={6} />}
      >
        {(r) =>
          r.variants.length === 0 ? (
            <BeatBlock className="rounded-lg border border-ay-border px-3 py-8 text-center text-sm text-ay-muted">
              {r.note ?? 'No backtest run yet.'}
            </BeatBlock>
          ) : (
            <div className="flex flex-col gap-4">
              <PortfolioTable variants={r.variants} />
              {r.variants.map((v) => (
                <VariantSetups key={v.variant} v={v} />
              ))}
              {r.note && <p className="text-xs text-ay-muted">{r.note}</p>}
            </div>
          )
        }
      </QueryState>
    </LoadBeat>
  );
}

/** The net-of-cost RS-priority portfolio (falls back to gross RS-priority, then plain). */
function portfolioOf(v: ManasReport) {
  return v.portfolioRsPriorityNet ?? v.portfolioRsPriority ?? v.portfolio;
}

// Headline A/B portfolio columns (one row per variant). Same content/order as the hand-rolled table
// it replaced — DataTable adds zebra + sticky header + the mobile card list.
const portfolioColumns: DataColumn<ManasReport>[] = [
  {
    id: 'variant',
    header: 'Variant',
    align: 'left',
    mobileLabel: 'Variant',
    cellClassName: () => 'font-medium text-ay-text',
    render: (v) => (
      <>
        {v.variant}
        {v.variant === PRIMARY_VARIANT && (
          <span className="ml-1 text-[10px] uppercase text-accent">live</span>
        )}
      </>
    ),
  },
  { id: 'trades', header: 'Trades', align: 'right', mobileLabel: 'Trades', render: (v) => v.totalTrades },
  { id: 'cagr', header: 'CAGR', align: 'right', mobileLabel: 'CAGR', render: (v) => signedPct(portfolioOf(v)?.cagrPct) },
  {
    id: 'totalRet',
    header: 'Total ret',
    align: 'right',
    mobileLabel: 'Total ret',
    render: (v) => signedPct(portfolioOf(v)?.totalReturnPct),
  },
  {
    id: 'maxDd',
    header: 'Max DD',
    align: 'right',
    mobileLabel: 'Max DD',
    cellClassName: () => 'text-bear',
    render: (v) => plain(portfolioOf(v)?.maxDrawdownPct),
  },
  { id: 'sharpe', header: 'Sharpe', align: 'right', mobileLabel: 'Sharpe', render: (v) => plain(portfolioOf(v)?.sharpe, 2) },
  {
    id: 'posMonths',
    header: '+Months',
    align: 'right',
    mobileLabel: '+Months',
    render: (v) => plain(portfolioOf(v)?.positiveMonthsPct),
  },
];

/** One row per variant: its RS-priority (net-of-cost) portfolio summary — the headline A/B numbers. */
function PortfolioTable({ variants }: { variants: ManasReport[] }) {
  return (
    <BeatBlock>
      <h3 className="mb-2 text-sm font-semibold text-ay-text">
        Portfolio comparison (RS-priority, net of cost)
      </h3>
      <DataTable
        columns={portfolioColumns}
        rows={variants}
        rowKey={(v) => String(v.variant)}
        rowClassName={(v) => (v.variant === PRIMARY_VARIANT ? 'bg-accent/5' : '')}
        ariaLabel="Portfolio comparison"
      />
    </BeatBlock>
  );
}

// Per-setup trade stats columns (breakout / vcp / ALL). Same content/order as the hand-rolled table.
const setupColumns: DataColumn<ManasSetupStat>[] = [
  {
    id: 'setup',
    header: 'Setup',
    align: 'left',
    mobileLabel: 'Setup',
    cellClassName: () => 'uppercase text-ay-text',
    render: (s) => s.setup,
  },
  { id: 'trades', header: 'Trades', align: 'right', mobileLabel: 'Trades', render: (s) => s.trades },
  { id: 'winRate', header: 'Win %', align: 'right', mobileLabel: 'Win %', render: (s) => plain(s.winRatePct) },
  {
    id: 'expectancy',
    header: 'Expectancy',
    align: 'right',
    mobileLabel: 'Expectancy',
    render: (s) => signedPct(s.expectancyPct),
  },
  { id: 'payoff', header: 'Payoff', align: 'right', mobileLabel: 'Payoff', render: (s) => plain(s.payoffRatio, 2) },
  {
    id: 'avgWin',
    header: 'Avg win',
    align: 'right',
    mobileLabel: 'Avg win',
    cellClassName: () => 'text-bull',
    render: (s) => plain(s.avgWinPct),
  },
  {
    id: 'avgLoss',
    header: 'Avg loss',
    align: 'right',
    mobileLabel: 'Avg loss',
    cellClassName: () => 'text-bear',
    render: (s) => plain(s.avgLossPct),
  },
  {
    id: 'profitFactor',
    header: 'Profit factor',
    align: 'right',
    mobileLabel: 'Profit factor',
    render: (s) => plain(s.profitFactor, 2),
  },
  { id: 'avgHold', header: 'Avg hold', align: 'right', mobileLabel: 'Avg hold', render: (s) => plain(s.avgBarsHeld, 0) },
  { id: 'stopOut', header: 'Stop-out %', align: 'right', mobileLabel: 'Stop-out %', render: (s) => plain(s.stopOutPct) },
];

/** Per-setup trade stats for one variant (breakout / vcp / ALL). */
function VariantSetups({ v }: { v: ManasReport }) {
  return (
    <BeatBlock>
      <h3 className="mb-2 text-sm font-semibold text-ay-text">
        {v.variant} · per-setup stats
        <span className="ml-2 text-xs font-normal text-ay-muted">
          {v.symbolsScanned} symbols · {v.totalTrades} trades
        </span>
      </h3>
      <DataTable
        columns={setupColumns}
        rows={v.setups}
        rowKey={(s) => s.setup}
        rowClassName={(s) => (s.setup === 'ALL' ? 'bg-surface-1 font-medium' : '')}
        ariaLabel={`${v.variant} per-setup stats`}
        emptyMessage="No trades for this variant."
      />
    </BeatBlock>
  );
}

/** These decimals are ALREADY in percent units (server sends e.g. "5.10" = 5.10%). */
function plain(v?: string | null, dp = 2): string {
  if (v == null) return '—';
  return Number.isFinite(Number(v)) ? formatDecimal(v, dp) : '—';
}

function signedPct(v?: string | null): string {
  if (v == null) return '—';
  const n = Number(v);
  if (!Number.isFinite(n)) return '—';
  return `${n > 0 ? '+' : ''}${formatDecimal(v, 2)}%`;
}
