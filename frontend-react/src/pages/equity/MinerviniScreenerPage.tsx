import { useState } from 'react';
import { m } from 'motion/react';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import { useMinerviniScreen, useRunMinervini, type ScreenParams } from '../../api/minervini.ts';

// /equity/minervini (Track-1): the daily Minervini SEPA momentum screener — the 8-gate Trend Template
// + cross-sectional RS-rank + Stage over the low-cap NSE equity universe. Shows the persisted screen;
// "Recompute" re-runs it. Low-cap free-float columns populate once the Upstox fundamentals feed loads.

const GATE_TITLES = [
  '1. Price > 150- & 200-day MA',
  '2. 150-day MA > 200-day MA',
  '3. 200-day MA rising ≥ 1 month',
  '4. 50-day MA > 150- & 200-day MA',
  '5. Price > 50-day MA',
  '6. Price ≥ 25% above 52-week low',
  '7. Price within 25% of 52-week high',
  '8. RS-rank ≥ 70',
];
const STAGE_LABEL: Record<number, string> = { 1: 'Stage 1', 2: 'Stage 2', 3: 'Stage 3', 4: 'Stage 4' };

export function MinerviniScreenerPage() {
  const [passesAllOnly, setPassesAllOnly] = useState(true);
  const [minRsRank, setMinRsRank] = useState(70);
  const params: ScreenParams = { passesAllOnly, minRsRank, limit: 200 };
  const screen = useMinerviniScreen(params);
  const run = useRunMinervini();

  const recompute = () => run.mutate(params, { onSuccess: () => screen.refetch() });
  const data = screen.data;
  const inputCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

  return (
    <LoadBeat>
      <PageHeader
        title="Minervini SEPA screener"
        subtitle="Daily 8-gate Trend Template + RS-rank over the low-cap NSE equity universe"
        help="Long-only momentum screener (Mark Minervini SEPA). A candidate must pass all 8 Trend-Template gates and rank ≥70 on relative strength. The daily screen runs after the EOD bhavcopy; use Recompute to re-run now. Low-cap free-float columns populate once the fundamentals feed is loaded."
      />

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-1.5 text-sm text-ay-text" title="Show only candidates that pass all 8 gates.">
          <input type="checkbox" checked={passesAllOnly} onChange={(e) => setPassesAllOnly(e.target.checked)} />
          Passers only
        </label>
        <label className="flex items-center gap-1.5 text-sm text-ay-text" title="Minimum RS-rank percentile.">
          Min RS
          <input
            type="number" min={0} max={100} value={minRsRank}
            onChange={(e) => setMinRsRank(Number(e.target.value))}
            className={`${inputCls} w-20`} aria-label="Minimum RS-rank"
          />
        </label>
        <button
          type="button" onClick={recompute} disabled={run.isPending}
          title="Recompute the screen now (re-runs the 8 gates + RS-rank over the universe)."
          className="h-9 rounded-md bg-accent px-3 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
        >
          {run.isPending ? 'Recomputing…' : 'Recompute'}
        </button>
        {data && (
          <span className="text-xs text-ay-muted">
            {data.screenDate ?? '—'} · {data.items.length} shown · {data.coverage} scanned
          </span>
        )}
      </div>

      <m.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.15 }}>
        <QueryState
          query={screen}
          empty={{ title: 'No screen yet — Recompute to run the daily Trend-Template screen.' }}
          errorTitle="Couldn't load the screen"
          skeleton={<Skeleton variant="table-rows" rows={10} cols={8} />}
        >
          {() => (
            <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
              <table className="w-full border-collapse text-sm">
                <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                  <tr>
                    <th className="px-2 py-2 font-medium">Symbol</th>
                    <th className="px-2 py-2 text-right font-medium">Close</th>
                    <th className="px-2 py-2 text-right font-medium">RS</th>
                    <th className="px-2 py-2 font-medium">Stage</th>
                    <th className="px-2 py-2 text-right font-medium">% from high</th>
                    <th className="px-2 py-2 text-right font-medium">% above low</th>
                    <th className="px-2 py-2 font-medium">Gates</th>
                    <th className="px-2 py-2 text-right font-medium">FF mcap (cr)</th>
                    <th className="px-2 py-2 text-right font-medium">FF %</th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.items ?? []).map((r) => (
                    <tr key={r.symbol} className="border-t border-ay-border">
                      <td className="px-2 py-2 font-medium">{r.symbol}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{formatDecimal(r.close, 2)}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{r.rsRank ? formatDecimal(r.rsRank, 0) : '—'}</td>
                      <td className="px-2 py-2">{r.stage ? STAGE_LABEL[r.stage] : '—'}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{pct(r.pctFromHigh)}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{pct(r.pctAboveLow)}</td>
                      <td className="px-2 py-2">
                        <span className="flex gap-0.5" title={`${r.gatesPassed}/8 gates`}>
                          {r.gates.map((g, i) => (
                            <span
                              key={i}
                              title={GATE_TITLES[i]}
                              className={cn(
                                'inline-flex h-4 w-4 items-center justify-center rounded text-[10px] font-bold',
                                g ? 'bg-bull/20 text-bull' : 'bg-bear/20 text-bear',
                              )}
                            >
                              {i + 1}
                            </span>
                          ))}
                        </span>
                      </td>
                      <td className="px-2 py-2 text-right tabular-nums">{r.freeFloatMcapCr ? formatDecimal(r.freeFloatMcapCr, 0) : '—'}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{r.freeFloatPct ? formatDecimal(r.freeFloatPct, 1) : '—'}</td>
                    </tr>
                  ))}
                  {(data?.items ?? []).length === 0 && (
                    <tr>
                      <td colSpan={9} className="px-2 py-6 text-center text-ay-muted">
                        No candidates for this screen.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </BeatBlock>
          )}
        </QueryState>
      </m.div>
    </LoadBeat>
  );
}

function pct(v?: string | null): string {
  if (v == null) return '—';
  const n = Number(v) * 100;
  return Number.isFinite(n) ? `${n.toFixed(1)}%` : '—';
}
