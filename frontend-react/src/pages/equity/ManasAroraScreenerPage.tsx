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
  useManasScreen,
  useManasFunnel,
  useRunManas,
  type ManasFunnelRow,
  type ManasRow,
  type ManasScreenParams,
} from '../../api/manasArora.ts';

// /equity/manas-arora: the daily Manas Arora selection screener. Two views: the flat 6-criteria
// SCREEN (§4.1 selection filter + §1.2 universe + §4.3 liquidity + §4.4 float), and the FUNNEL that
// ranks the passers into the actionable triad (immediately-buyable / on-deck / watch) by proximity to
// the vcp/breakout pivot. Recompute re-runs the screen. Mirrors MinerviniScreenerPage.

const GATE_TITLES = [
  '1. Price ≥ ₹30',
  '2. 200-day MA rising ≥ 3 months',
  '3. 50-day MA > 200-day MA',
  '4. Price > 200-day MA',
  '5. Price ≥ 100% above 52-week low',
  '6. New 52-week high made recently',
];

type View = 'screen' | 'funnel';

export function ManasAroraScreenerPage() {
  const [view, setView] = useState<View>('screen');
  const [passesAllOnly, setPassesAllOnly] = useState(true);
  const params: ManasScreenParams = { passesAllOnly, limit: 200 };
  const screen = useManasScreen(params, view === 'screen');
  const funnel = useManasFunnel(undefined, view === 'funnel');
  const run = useRunManas();

  const recompute = () => run.mutate(params, { onSuccess: () => screen.refetch() });
  const data = screen.data;

  return (
    <LoadBeat>
      <PageHeader
        title="Manas Arora selection screener"
        subtitle="Daily 6-criteria selection filter + within-25%-of-high universe + liquidity/float over the NSE equity universe"
        help="Long-only momentum screener (Manas Arora — a simplified, India-adapted Minervini). A candidate must pass all 6 §4.1 selection gates; it must also sit within the within-25%-of-high universe and clear the liquidity/float vetoes. The Funnel view ranks the passers into immediately-buyable / on-deck / watch by proximity to the vcp/breakout pivot. The daily screen runs after the EOD bhavcopy; use Recompute to re-run now."
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
            <label
              className="flex items-center gap-1.5 text-sm text-ay-text"
              title="Show only candidates that pass all 6 selection gates."
            >
              <input
                type="checkbox"
                checked={passesAllOnly}
                onChange={(e) => setPassesAllOnly(e.target.checked)}
              />
              Passers only
            </label>
            <button
              type="button"
              onClick={recompute}
              disabled={run.isPending}
              title="Recompute the screen now (re-runs the 6 gates + universe/liquidity/float over the universe)."
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
            empty={{ title: 'No screen yet — Recompute to run the daily selection screen.' }}
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
                      <th className="px-2 py-2 text-right font-medium">% from high</th>
                      <th className="px-2 py-2 text-right font-medium">% above low</th>
                      <th className="px-2 py-2 font-medium">Gates</th>
                      <th className="px-2 py-2 font-medium">Flags</th>
                      <th className="px-2 py-2 text-right font-medium">Turnover 50d</th>
                      <th className="px-2 py-2 text-right font-medium">FF mcap (cr)</th>
                      <th className="px-2 py-2 text-right font-medium">FF %</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(data?.items ?? []).map((r) => (
                      <tr key={r.symbol} className="border-t border-ay-border">
                        <td className="px-2 py-2 font-medium">
                          <Link
                            to={`/equity/manas-arora/${r.symbol}`}
                            className="text-accent hover:underline"
                          >
                            {r.symbol}
                          </Link>
                        </td>
                        <td className="px-2 py-2 text-right tabular-nums">
                          {formatDecimal(r.close, 2)}
                        </td>
                        <td className="px-2 py-2 text-right tabular-nums">{pct(r.withinHighPct)}</td>
                        <td className="px-2 py-2 text-right tabular-nums">{pct(r.aboveLowPct)}</td>
                        <td className="px-2 py-2">
                          <span className="flex gap-0.5" title={`${r.gatesPassed}/6 gates`}>
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
                        <td className="px-2 py-2">
                          <Flags r={r} />
                        </td>
                        <td className="px-2 py-2 text-right tabular-nums">
                          {r.turnover50 ? formatDecimal(r.turnover50, 0) : '—'}
                        </td>
                        <td className="px-2 py-2 text-right tabular-nums">
                          {r.freeFloatMcapCr ? formatDecimal(r.freeFloatMcapCr, 0) : '—'}
                        </td>
                        <td className="px-2 py-2 text-right tabular-nums">
                          {r.freeFloatPct ? formatDecimal(r.freeFloatPct, 1) : '—'}
                        </td>
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
                  <FunnelColumn
                    title="Immediately buyable"
                    hint="valid base, price at the pivot"
                    tone="bull"
                    rows={f.immediatelyBuyable}
                  />
                  <FunnelColumn
                    title="On deck"
                    hint="valid base, tightening toward the pivot"
                    tone="accent"
                    rows={f.onDeck}
                  />
                  <FunnelColumn
                    title="Watch"
                    hint="no valid base yet, or extended past the pivot"
                    tone="muted"
                    rows={f.watch}
                  />
                </div>
              </>
            )}
          </QueryState>
        )}
      </m.div>
    </LoadBeat>
  );
}

function Flags({ r }: { r: ManasRow }) {
  const flags: [string, boolean, string][] = [
    ['H', r.withinHigh, 'Within 25% of the 52-week high (universe)'],
    ['V', r.liquidVolume, 'Liquid — 20-day average volume clears the floor'],
    ['D', r.liquidDepth, 'Liquid depth — 50-day average volume clears the floor'],
    ['C', r.lowCap, 'Low-cap free-float gate (§4.4)'],
  ];
  return (
    <span className="flex gap-0.5">
      {flags.map(([label, on, title]) => (
        <span
          key={label}
          title={title}
          className={cn(
            'inline-flex h-4 w-4 items-center justify-center rounded text-[10px] font-bold',
            on ? 'bg-bull/20 text-bull' : 'bg-surface-2 text-ay-muted',
          )}
        >
          {label}
        </span>
      ))}
    </span>
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
  rows: ManasFunnelRow[];
}) {
  const dot = tone === 'bull' ? 'bg-bull' : tone === 'accent' ? 'bg-accent' : 'bg-ay-muted';
  return (
    <BeatBlock className="rounded-lg border border-ay-border">
      <div className="border-b border-ay-border px-3 py-2">
        <div className="flex items-center gap-2">
          <span className={cn('h-2 w-2 rounded-full', dot)} aria-hidden="true" />
          <h3 className="text-sm font-semibold text-ay-text">{title}</h3>
          <span className="ml-auto rounded bg-surface-2 px-1.5 text-xs tabular-nums text-ay-muted">
            {rows.length}
          </span>
        </div>
        <p className="mt-0.5 text-xs text-ay-muted">{hint}</p>
      </div>
      <ul className="max-h-[32rem] divide-y divide-ay-border overflow-auto">
        {rows.map((r) => (
          <li key={r.symbol} className="flex items-center gap-2 px-3 py-2">
            <Link
              to={`/equity/manas-arora/${r.symbol}`}
              className="font-medium text-accent hover:underline"
            >
              {r.symbol}
            </Link>
            {r.setupType && (
              <span
                className="rounded bg-surface-2 px-1 text-[10px] uppercase text-ay-muted"
                title="Base setup at/near the pivot"
              >
                {r.setupType}
              </span>
            )}
            {r.footprint && <span className="text-[11px] text-ay-muted">{r.footprint}</span>}
            <span className="ml-auto flex items-center gap-3 text-xs tabular-nums text-ay-muted">
              <span title="Close / pivot">
                {formatDecimal(r.close, 2)}
                {r.pctToPivot != null && (
                  <span className="ml-1 text-ay-muted/70">({pct(r.pctToPivot)})</span>
                )}
              </span>
            </span>
          </li>
        ))}
        {rows.length === 0 && (
          <li className="px-3 py-6 text-center text-sm text-ay-muted">None.</li>
        )}
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
