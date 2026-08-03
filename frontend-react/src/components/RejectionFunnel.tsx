import { useCallback, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { EChart, type ChartTheme } from './atoms/EChart.tsx';
import { Select } from './atoms/Select.tsx';
import {
  useEvalFunnel,
  useRejectionRailCounts,
  type EvalOutcomeCount,
} from '../api/signalRejections.ts';

// The per-strategy evaluation FUNNEL for one IST session day (signal-analysis README §7 row 7): how a
// day's bars narrow from the true denominator, through each place the engine says no, down to the ones
// that fired — with the confluence-blocked bucket fanned out by its blocking rail.
//
// WHY THE DENOMINATOR IS THE WHOLE POINT. Every rate here is computed against `strategy_eval_denominator`
// (V053, F5 unit U2), which the engine writes per strategy per session date. Before it existed a funnel
// could only guess its first stage off the 3m grid, which is wrong whenever strategies differ in session
// window, universe or load date — a funnel without a true denominator is exactly what U2 was built to fix.
//
// WHY IT IS LAZY-LOADED. /signal-rejections is an EAGER route, and the EChart atom pulls the ~1 MB echarts
// vendor bundle. The page imports this file through React.lazy so that bundle stays a separate chunk
// (FE-01) instead of landing in the main payload for every visitor to every page.

/**
 * Where bars LEAK out of the ladder, in the order the engine reaches those outcomes. Each entry is one
 * `SignalEngine.Outcome` tag; the survivors after it flow into `survivor`, and whatever is left at the
 * end is `fired`.
 *
 * <p>The order is the engine's, not a presentation choice: an unscoreable bar never reaches the chart
 * gates, a failed chart gate never consults the composite, and the three scalper-confluence outcomes
 * only exist for a bar whose chart entry already fired. Re-ordering these rows would draw a flow the
 * engine cannot produce.
 */
const LEAKS: { outcome: string; label: string; survivor: string; why: string }[] = [
  {
    outcome: 'unscoreable-indicators-warming',
    label: 'Indicators warming',
    survivor: 'Scoreable',
    why: 'A required scoring participant was null, so the bar could not be scored at all. The one outcome that indicates a FAULT rather than a market “no”.',
  },
  {
    outcome: 'chart-gate-failed',
    label: 'Chart gate said no',
    survivor: 'Chart gates passed',
    why: 'A chart gate rule said no. The composite is never consulted once a gate fails.',
  },
  {
    outcome: 'composite-below-threshold',
    label: 'Composite below threshold',
    survivor: 'Chart entry fired',
    why: 'Every chart gate passed and the composite still came in under the strategy’s scoring threshold.',
  },
  {
    outcome: 'discipline-paused',
    label: 'Discipline freeze',
    survivor: 'Discipline open',
    why: 'The five-account discipline (5 losses freeze / 5 wins bank the day) held entries for the rest of the session — a deliberate freeze, and the confluence was never consulted.',
  },
  {
    outcome: 'confluence-gate-absent',
    label: 'Confluence gate absent',
    survivor: 'Gate present',
    why: 'A scalper is loaded but its confluence seam is absent, so it can never fire (fail-closed). A misconfiguration, not a market “no”.',
  },
  {
    outcome: 'confluence-blocked',
    label: 'Confluence gate blocked',
    survivor: 'Fired',
    why: 'The chart said yes and the confluence gate blocked it. This is the ONLY outcome that writes a signal_rejections row — which is why the table can be empty all session on a down tape.',
  },
];

const FIRED = 'fired';

/** One rung of the computed ladder: what entered, what leaked here, and what survived. */
interface Rung {
  outcome: string;
  label: string;
  survivor: string;
  why: string;
  entering: number;
  leaked: number;
  remaining: number;
}

interface Ladder {
  evaluations: number;
  rungs: Rung[];
  fired: number;
  /**
   * Outcome tags this UI does not know about (a tag added to the engine after this file was written).
   * Surfaced rather than dropped: silently discarding them would make `evaluations` and the ladder
   * disagree with no visible reason.
   */
  unknown: EvalOutcomeCount[];
}

/** Folds one strategy's outcome totals into the ladder. Pure — the whole funnel is derived here. */
function buildLadder(rows: EvalOutcomeCount[]): Ladder {
  const byOutcome = new Map<string, number>();
  for (const r of rows) byOutcome.set(r.outcome, (byOutcome.get(r.outcome) ?? 0) + r.evalCount);
  const evaluations = rows.reduce((sum, r) => sum + r.evalCount, 0);

  let remaining = evaluations;
  const rungs = LEAKS.map((leak) => {
    const entering = remaining;
    const leaked = byOutcome.get(leak.outcome) ?? 0;
    remaining -= leaked;
    return { ...leak, entering, leaked, remaining };
  });

  const known = new Set<string>([...LEAKS.map((l) => l.outcome), FIRED]);
  return {
    evaluations,
    rungs,
    fired: byOutcome.get(FIRED) ?? 0,
    unknown: rows.filter((r) => !known.has(r.outcome)),
  };
}

function pct(part: number, whole: number): string {
  if (whole <= 0) return '—';
  return `${((100 * part) / whole).toFixed(1)}%`;
}

const inIN = (n: number) => n.toLocaleString('en-IN');

/**
 * The Sankey flow: one node per surviving stage, one leaf per leak, and — under the confluence-blocked
 * leak only — one leaf per blocking rail.
 *
 * <p>Zero-valued links are DROPPED and the node list is derived from the links that survive, so a stage
 * nothing reached is simply absent rather than drawn as a dangling box. That is also what makes the
 * common down-tape shape (everything leaks at the chart gate) render as a short, honest flow.
 *
 * <p>The confluence-blocked leak keeps the DENOMINATOR's count on its inbound link and hangs the rails
 * off it, rather than replacing it with the sum of the rails. The two come from different tables bounded
 * different ways (session date vs `generated_at`), so they can legitimately differ by a bar near the IST
 * midnight boundary — and when they do, the honest picture is a node whose inflow and outflow disagree,
 * not a silently rewritten total.
 */
function makeSankey(ladder: Ladder, rails: { rail: string; count: number }[]) {
  const links: { source: string; target: string; value: number }[] = [];
  const push = (source: string, target: string, value: number) => {
    if (value > 0) links.push({ source, target, value });
  };

  let stage = 'Evaluated';
  for (const rung of ladder.rungs) {
    push(stage, rung.label, rung.leaked);
    if (rung.outcome === 'confluence-blocked') {
      for (const r of rails) push(rung.label, r.rail, r.count);
    }
    push(stage, rung.survivor, rung.remaining);
    stage = rung.survivor;
  }

  const survivors = new Set<string>(['Evaluated', ...LEAKS.map((l) => l.survivor)]);
  const names = [...new Set(links.flatMap((l) => [l.source, l.target]))];
  return { links, names, survivors };
}

interface RejectionFunnelProps {
  /** The IST session date (YYYY-MM-DD) — the date of the evaluated BARS. */
  date: string;
  /** The same day as ISO `+05:30` bounds, for the rejection-side rail rollup. */
  from: string;
  to: string;
}

/**
 * Default-exported so the page can `React.lazy` it — see the bundle note at the top of this file.
 */
export default function RejectionFunnel({ date, from, to }: RejectionFunnelProps) {
  const [slug, setSlug] = useState<string>('');
  const funnel = useEvalFunnel(date);
  const rows = useMemo(() => funnel.data?.items ?? [], [funnel.data]);

  const slugs = useMemo(() => {
    const totals = new Map<string, number>();
    for (const r of rows) totals.set(r.strategySlug, (totals.get(r.strategySlug) ?? 0) + r.evalCount);
    return [...totals.entries()].sort((a, b) => b[1] - a[1]).map(([s]) => s);
  }, [rows]);

  // The busiest strategy is the default view; an explicit pick wins while it stays in the day's set.
  const selected = slug && slugs.includes(slug) ? slug : (slugs[0] ?? '');
  const railCounts = useRejectionRailCounts(null, from, to, selected || null);
  // Memoised, not `?? []` inline: a fresh literal each render would re-key the sankey memo below.
  const rails = useMemo(() => railCounts.data?.items ?? [], [railCounts.data]);

  const ladder = useMemo(
    () => buildLadder(rows.filter((r) => r.strategySlug === selected)),
    [rows, selected],
  );

  const sankey = useMemo(() => makeSankey(ladder, rails), [ladder, rails]);
  const makeOption = useCallback(
    (t: ChartTheme): EChartsOption => ({
      aria: { enabled: true },
      textStyle: { color: t.text },
      tooltip: {
        trigger: 'item',
        backgroundColor: t.surface1,
        borderColor: t.border,
        textStyle: { color: t.text },
      },
      series: [
        {
          type: 'sankey',
          left: 8,
          right: 120,
          top: 8,
          bottom: 8,
          emphasis: { focus: 'adjacency' },
          nodeAlign: 'left',
          label: { color: t.text, fontSize: 11 },
          lineStyle: { color: 'gradient', opacity: 0.35 },
          itemStyle: { borderColor: t.border },
          // Three roles, three tones: the surviving spine, the leaks that end a bar's day, and the
          // one node that means an entry actually happened.
          data: sankey.names.map((name) => ({
            name,
            itemStyle: {
              color: name === 'Fired' ? t.bull : sankey.survivors.has(name) ? t.accent : t.muted,
            },
          })),
          links: sankey.links,
        },
      ],
    }),
    [sankey],
  );

  return (
    <section
      className="mb-4 rounded-lg border border-ay-border bg-surface-1 p-3"
      aria-label="Per-strategy evaluation funnel"
    >
      <div className="mb-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
        <h2 className="text-sm font-semibold text-ay-text">
          Evaluation funnel{' '}
          <span className="font-normal text-ay-muted">· {funnel.data?.sessionDate ?? date}</span>
        </h2>
        {slugs.length > 0 && (
          <div className="flex flex-wrap items-center gap-2">
            <Select
              value={selected}
              ariaLabel="Funnel strategy"
              title="Every strategy that recorded an evaluation on this session date, busiest first."
              onChange={setSlug}
              options={slugs}
            />
            <span className="text-caption text-ay-muted">
              {inIN(ladder.evaluations)} evaluation{ladder.evaluations === 1 ? '' : 's'}
              {funnel.data && funnel.data.boots > 1 && (
                <span title="The day spans a restart; the totals are a SUM across boots (V053 keys boot_id so a restart adds rows instead of overwriting).">
                  {' '}
                  · {funnel.data.boots} boots
                </span>
              )}
            </span>
          </div>
        )}
      </div>

      {funnel.isLoading && <p className="text-caption text-ay-muted">Loading the funnel…</p>}
      {funnel.isError && (
        <p className="text-caption text-ay-muted">Evaluation denominator unavailable right now.</p>
      )}

      {funnel.data && rows.length === 0 && (
        // NOT "0 evaluations". The rollup writes no zero rows, so a date with no rows is a date
        // nothing was RECORDED for — a down stack, a pre-V053 day, or one past retention. Rendering
        // that as zeros would invent a measurement.
        <p className="text-caption text-ay-muted">
          No evaluation counts recorded for this session date, so the funnel has no denominator to
          stand on. That is not the same as “nothing was evaluated” — the rollup never writes zero
          rows, so a down stack, a day before the counters existed, and a day past retention all look
          like this.
        </p>
      )}

      {funnel.data && rows.length > 0 && (
        <>
          {sankey.links.length > 0 && (
            <EChart
              makeOption={makeOption}
              height={260}
              ariaLabel={`Evaluation funnel for ${selected} on ${date} — the stage-by-stage table below carries the same numbers`}
            />
          )}

          {/* Five numeric columns cannot fit the ~480px mobile target — scroll the TABLE, never the page. */}
          <div className="mt-2 overflow-x-auto">
            <table className="w-full min-w-[26rem] text-xs">
              <caption className="mb-1 text-left text-caption text-ay-muted">
                Where {selected}’s bars left the ladder on {date}. “% of evaluated” is against the real
                per-strategy denominator, never one inferred from the 3m grid.
              </caption>
              <thead>
                <tr className="border-b border-ay-border text-ay-muted">
                  <th scope="col" className="py-1 text-left font-medium">
                    Stage
                  </th>
                  <th scope="col" className="py-1 text-right font-medium">
                    Entering
                  </th>
                  <th scope="col" className="py-1 text-right font-medium">
                    Left here
                  </th>
                  <th scope="col" className="py-1 text-right font-medium">
                    Surviving
                  </th>
                  <th scope="col" className="py-1 text-right font-medium">
                    % of evaluated
                  </th>
                </tr>
              </thead>
              <tbody>
                {ladder.rungs.map((rung) => (
                  <tr key={rung.outcome} className="border-t border-ay-border/40">
                    <th scope="row" className="py-1 pr-2 text-left font-normal text-ay-text">
                      <span title={rung.why}>{rung.label}</span>
                    </th>
                    <td className="py-1 text-right tabular-nums text-ay-muted">
                      {inIN(rung.entering)}
                    </td>
                    <td className="py-1 text-right tabular-nums text-ay-text">{inIN(rung.leaked)}</td>
                    <td className="py-1 text-right tabular-nums text-ay-muted">
                      {inIN(rung.remaining)}
                    </td>
                    <td className="py-1 text-right tabular-nums text-ay-muted">
                      {pct(rung.leaked, ladder.evaluations)}
                    </td>
                  </tr>
                ))}
                <tr className="border-t border-ay-border">
                  <th scope="row" className="py-1 pr-2 text-left font-semibold text-ay-text">
                    Fired
                  </th>
                  <td className="py-1 text-right tabular-nums text-ay-muted">—</td>
                  <td className="py-1 text-right tabular-nums font-semibold text-bull">
                    {inIN(ladder.fired)}
                  </td>
                  <td className="py-1 text-right tabular-nums text-ay-muted">—</td>
                  <td className="py-1 text-right tabular-nums text-ay-muted">
                    {pct(ladder.fired, ladder.evaluations)}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          {rails.length > 0 && (
            <p className="mt-2 text-caption text-ay-muted">
              Blocking rails inside the confluence-blocked bucket:{' '}
              {rails.map((r, i) => (
                <span key={r.rail}>
                  {i > 0 ? ' · ' : ''}
                  <span className="text-ay-text">{r.rail}</span> {inIN(r.count)}
                </span>
              ))}
              . Counted from the rejection rows in this IST day, so a bar evaluated near midnight can
              sit on the other side of the boundary from its denominator row.
            </p>
          )}

          {ladder.unknown.length > 0 && (
            <p className="mt-2 text-caption text-warn">
              {ladder.unknown.length} outcome tag
              {ladder.unknown.length === 1 ? '' : 's'} this view does not know about (
              {ladder.unknown.map((u) => u.outcome).join(', ')}) — counted in the denominator but not
              placed on a rung, so the stages below will not add up until they are mapped.
            </p>
          )}
        </>
      )}
    </section>
  );
}
