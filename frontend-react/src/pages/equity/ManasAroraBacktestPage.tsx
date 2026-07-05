import { useState } from 'react';
import { Link } from 'react-router-dom';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
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

/** The variant the plain funnel analogue reports (the full-filter run). */
const PRIMARY_VARIANT = 'rs-turnover-pyramid';

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

/** One row per variant: its RS-priority (net-of-cost) portfolio summary — the headline A/B numbers. */
function PortfolioTable({ variants }: { variants: ManasReport[] }) {
  return (
    <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
      <h3 className="border-b border-ay-border bg-surface-1 px-3 py-2 text-sm font-semibold text-ay-text">
        Portfolio comparison (RS-priority, net of cost)
      </h3>
      <table className="w-full border-collapse text-sm">
        <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
          <tr>
            <th className="px-2 py-2 font-medium">Variant</th>
            <th className="px-2 py-2 text-right font-medium">Trades</th>
            <th className="px-2 py-2 text-right font-medium">CAGR</th>
            <th className="px-2 py-2 text-right font-medium">Total ret</th>
            <th className="px-2 py-2 text-right font-medium">Max DD</th>
            <th className="px-2 py-2 text-right font-medium">Sharpe</th>
            <th className="px-2 py-2 text-right font-medium">+Months</th>
          </tr>
        </thead>
        <tbody>
          {variants.map((v) => {
            const p = v.portfolioRsPriorityNet ?? v.portfolioRsPriority ?? v.portfolio;
            return (
              <tr
                key={v.variant}
                className={cn(
                  'border-t border-ay-border',
                  v.variant === PRIMARY_VARIANT && 'bg-accent/5',
                )}
              >
                <td className="px-2 py-2 font-medium text-ay-text">
                  {v.variant}
                  {v.variant === PRIMARY_VARIANT && (
                    <span className="ml-1 text-[10px] uppercase text-accent">live</span>
                  )}
                </td>
                <td className="px-2 py-2 text-right tabular-nums">{v.totalTrades}</td>
                <td className="px-2 py-2 text-right tabular-nums">{signedPct(p?.cagrPct)}</td>
                <td className="px-2 py-2 text-right tabular-nums">{signedPct(p?.totalReturnPct)}</td>
                <td className="px-2 py-2 text-right tabular-nums text-bear">{plain(p?.maxDrawdownPct)}</td>
                <td className="px-2 py-2 text-right tabular-nums">{plain(p?.sharpe, 2)}</td>
                <td className="px-2 py-2 text-right tabular-nums">{plain(p?.positiveMonthsPct)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </BeatBlock>
  );
}

/** Per-setup trade stats for one variant (breakout / vcp / ALL). */
function VariantSetups({ v }: { v: ManasReport }) {
  return (
    <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
      <h3 className="border-b border-ay-border bg-surface-1 px-3 py-2 text-sm font-semibold text-ay-text">
        {v.variant} · per-setup stats
        <span className="ml-2 text-xs font-normal text-ay-muted">
          {v.symbolsScanned} symbols · {v.totalTrades} trades
        </span>
      </h3>
      <table className="w-full border-collapse text-sm">
        <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
          <tr>
            <th className="px-2 py-2 font-medium">Setup</th>
            <th className="px-2 py-2 text-right font-medium">Trades</th>
            <th className="px-2 py-2 text-right font-medium">Win %</th>
            <th className="px-2 py-2 text-right font-medium">Expectancy</th>
            <th className="px-2 py-2 text-right font-medium">Payoff</th>
            <th className="px-2 py-2 text-right font-medium">Avg win</th>
            <th className="px-2 py-2 text-right font-medium">Avg loss</th>
            <th className="px-2 py-2 text-right font-medium">Profit factor</th>
            <th className="px-2 py-2 text-right font-medium">Avg hold</th>
            <th className="px-2 py-2 text-right font-medium">Stop-out %</th>
          </tr>
        </thead>
        <tbody>
          {v.setups.map((s) => (
            <SetupRow key={s.setup} s={s} />
          ))}
          {v.setups.length === 0 && (
            <tr>
              <td colSpan={10} className="px-2 py-6 text-center text-ay-muted">
                No trades for this variant.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </BeatBlock>
  );
}

function SetupRow({ s }: { s: ManasSetupStat }) {
  return (
    <tr
      className={cn('border-t border-ay-border', s.setup === 'ALL' && 'bg-surface-1 font-medium')}
    >
      <td className="px-2 py-2 uppercase text-ay-text">{s.setup}</td>
      <td className="px-2 py-2 text-right tabular-nums">{s.trades}</td>
      <td className="px-2 py-2 text-right tabular-nums">{plain(s.winRatePct)}</td>
      <td className="px-2 py-2 text-right tabular-nums">{signedPct(s.expectancyPct)}</td>
      <td className="px-2 py-2 text-right tabular-nums">{plain(s.payoffRatio, 2)}</td>
      <td className="px-2 py-2 text-right tabular-nums text-bull">{plain(s.avgWinPct)}</td>
      <td className="px-2 py-2 text-right tabular-nums text-bear">{plain(s.avgLossPct)}</td>
      <td className="px-2 py-2 text-right tabular-nums">{plain(s.profitFactor, 2)}</td>
      <td className="px-2 py-2 text-right tabular-nums">{plain(s.avgBarsHeld, 0)}</td>
      <td className="px-2 py-2 text-right tabular-nums">{plain(s.stopOutPct)}</td>
    </tr>
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
