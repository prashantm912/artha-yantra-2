import { useState } from 'react';
import { Link } from 'react-router-dom';
import { m } from 'motion/react';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  useMinerviniScreen,
  useMinerviniFunnel,
  useRunMinervini,
  type FunnelRow,
  type ScreenParams,
} from '../../api/minervini.ts';

// /equity/minervini (Track-1 + Track-B): the daily Minervini SEPA screener. Two views: the flat
// 8-gate Trend-Template SCREEN, and the SEPA FUNNEL that ranks the passers into the actionable triad
// (immediately-buyable / on-deck / watch) by VCP-pivot proximity. Recompute re-runs the screen.

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

type View = 'screen' | 'funnel';

export function MinerviniScreenerPage() {
  const [view, setView] = useState<View>('screen');
  const [passesAllOnly, setPassesAllOnly] = useState(true);
  const [minRsRank, setMinRsRank] = useState(70);
  const params: ScreenParams = { passesAllOnly, minRsRank, limit: 200 };
  const screen = useMinerviniScreen(params, view === 'screen');
  const funnel = useMinerviniFunnel(undefined, view === 'funnel');
  const run = useRunMinervini();

  const recompute = () => run.mutate(params, { onSuccess: () => screen.refetch() });
  const data = screen.data;
  const inputCls = 'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text';

  return (
    <LoadBeat>
      <PageHeader
        title="Minervini SEPA screener"
        subtitle="Daily 8-gate Trend Template + RS-rank over the low-cap NSE equity universe"
        help="Long-only momentum screener (Mark Minervini SEPA). A candidate must pass all 8 Trend-Template gates and rank ≥70 on relative strength. The Funnel view ranks the passers into immediately-buyable / on-deck / watch by proximity to the VCP breakout pivot. The daily screen runs after the EOD bhavcopy; use Recompute to re-run now."
      />

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <div role="tablist" aria-label="View" className="flex rounded-md border border-ay-border p-0.5">
          {(['screen', 'funnel'] as View[]).map((v) => (
            <button
              key={v}
              type="button"
              role="tab"
              aria-selected={view === v}
              onClick={() => setView(v)}
              className={cn(
                'h-8 rounded px-3 text-sm capitalize',
                view === v ? 'bg-accent text-surface-0' : 'text-ay-text hover:bg-surface-2',
              )}
            >
              {v}
            </button>
          ))}
        </div>
        {view === 'screen' && (
          <>
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
          </>
        )}
        {view === 'funnel' && funnel.data && (
          <span className="text-xs text-ay-muted">
            {funnel.data.screenDate ?? '—'} · {funnel.data.immediatelyBuyable.length} buyable ·{' '}
            {funnel.data.onDeck.length} on-deck · {funnel.data.watch.length} watch
          </span>
        )}
      </div>

      <m.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.15 }}>
        {view === 'screen' ? (
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
                        <td className="px-2 py-2 font-medium">
                          <Link to={`/equity/minervini/${r.symbol}`} className="text-accent hover:underline">
                            {r.symbol}
                          </Link>
                        </td>
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
        ) : (
          <QueryState
            query={funnel}
            empty={{ title: 'No funnel yet — the screen has no passers for this date.' }}
            errorTitle="Couldn't load the funnel"
            skeleton={<Skeleton variant="table-rows" rows={8} cols={3} />}
          >
            {(f) => (
              <>
                {f.regime && <RegimeBanner regime={f.regime.regime} ratio={f.regime.advanceRatio} />}
                <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
                  <FunnelColumn title="Immediately buyable" hint="valid VCP, price at the pivot" tone="bull" rows={f.immediatelyBuyable} />
                  <FunnelColumn title="On deck" hint="valid VCP, tightening toward the pivot" tone="accent" rows={f.onDeck} />
                  <FunnelColumn title="Watch" hint="no valid base yet, or extended past the pivot" tone="muted" rows={f.watch} />
                </div>
              </>
            )}
          </QueryState>
        )}
      </m.div>
    </LoadBeat>
  );
}

function FunnelColumn({
  title,
  hint,
  tone,
  rows,
}: {
  title: string;
  hint: string;
  tone: 'bull' | 'accent' | 'muted';
  rows: FunnelRow[];
}) {
  const dot = tone === 'bull' ? 'bg-bull' : tone === 'accent' ? 'bg-accent' : 'bg-ay-muted';
  return (
    <BeatBlock className="rounded-lg border border-ay-border">
      <div className="border-b border-ay-border px-3 py-2">
        <div className="flex items-center gap-2">
          <span className={cn('h-2 w-2 rounded-full', dot)} aria-hidden="true" />
          <h3 className="text-sm font-semibold text-ay-text">{title}</h3>
          <span className="ml-auto rounded bg-surface-2 px-1.5 text-xs tabular-nums text-ay-muted">{rows.length}</span>
        </div>
        <p className="mt-0.5 text-xs text-ay-muted">{hint}</p>
      </div>
      <ul className="max-h-[32rem] divide-y divide-ay-border overflow-auto">
        {rows.map((r) => (
          <li key={r.symbol} className="flex items-center gap-2 px-3 py-2">
            <Link to={`/equity/minervini/${r.symbol}`} className="font-medium text-accent hover:underline">
              {r.symbol}
            </Link>
            {r.footprint && <span className="text-[11px] text-ay-muted">{r.footprint}</span>}
            <span className="ml-auto flex items-center gap-3 text-xs tabular-nums text-ay-muted">
              <span title="Close / pivot">
                {formatDecimal(r.close, 2)}
                {r.pctToPivot != null && <span className="ml-1 text-ay-muted/70">({pct(r.pctToPivot)})</span>}
              </span>
              <span title="RS-rank" className="text-ay-text">RS {r.rsRank ? formatDecimal(r.rsRank, 0) : '—'}</span>
            </span>
          </li>
        ))}
        {rows.length === 0 && <li className="px-3 py-6 text-center text-sm text-ay-muted">None.</li>}
      </ul>
    </BeatBlock>
  );
}

function RegimeBanner({ regime, ratio }: { regime: string; ratio?: string | null }) {
  const tone =
    regime === 'FAVORABLE'
      ? 'border-bull/40 bg-bull/10 text-bull'
      : regime === 'HOSTILE'
        ? 'border-bear/40 bg-bear/10 text-bear'
        : 'border-ay-border bg-surface-1 text-ay-muted';
  const note =
    regime === 'FAVORABLE'
      ? 'Market with you — press the buyable breakouts.'
      : regime === 'HOSTILE'
        ? 'Hostile tape — hold off pressing new breakouts.'
        : 'Neutral tape — be selective.';
  const adv = ratio != null ? ` · ${(Number(ratio) * 100).toFixed(0)}% advancing` : '';
  return (
    <div className={cn('mb-3 rounded-lg border px-3 py-2 text-sm', tone)}>
      <span className="font-semibold capitalize">{regime.toLowerCase()} regime</span>
      <span className="text-ay-muted">
        {adv} — {note}
      </span>
    </div>
  );
}

function pct(v?: string | null): string {
  if (v == null) return '—';
  const n = Number(v) * 100;
  return Number.isFinite(n) ? `${n.toFixed(1)}%` : '—';
}
