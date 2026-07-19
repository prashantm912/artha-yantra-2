import { useMemo, useState } from 'react';
import { Lightbulb } from 'lucide-react';
import { Select } from '../../components/atoms/Select.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import {
  fmtNum,
  useSweepInsights,
  type BrittlenessItem,
  type Candidate,
  type Generation,
  type ImportanceItem,
} from '../../api/evolution.ts';

// The §10 Insights tab: parameter-effect analytics for one recorded generation's sweep. The backend
// keys these by SWEEP job id (`/insights/{sweepJobId}`), so this view resolves a generation → the
// sweepJobId carried on its candidates, then renders the importance tornado, the brittleness grid
// and the 2-D interaction slices. Pure CSS bars (not echarts) keep it accessible + the numbers
// on-screen (colour only reinforces the sign, never the sole signal).

/** direction → tone (positive effect reads bull, negative bear, flat/absent muted). */
const DIR_TONE: Record<string, string> = {
  positive: 'var(--ay-bull)',
  negative: 'var(--ay-bear)',
  flat: 'var(--ay-text-muted)',
};

function TornadoRow({ item, max }: { item: ImportanceItem; max: number }) {
  const pct = max > 0 ? Math.min(100, (item.score / max) * 100) : 0;
  const colour = DIR_TONE[item.direction ?? 'flat'] ?? 'var(--ay-text-muted)';
  return (
    <li className="grid grid-cols-[9rem_1fr_6rem] items-center gap-2 text-xs">
      <span className="truncate text-ay-text" title={item.param}>
        {item.param}
      </span>
      <span className="relative block h-3.5 overflow-hidden rounded bg-surface-2">
        <span
          aria-hidden="true"
          className="absolute inset-y-0 left-0 rounded"
          style={{ width: `${pct}%`, background: `color-mix(in srgb, ${colour} 45%, transparent)` }}
        />
      </span>
      <span className="text-right tabular-nums text-ay-muted">
        {fmtNum(item.score, 3)}
        {item.direction && item.direction !== 'flat' && (
          <span className="ml-1 text-[10px]">({item.direction === 'positive' ? '↑' : '↓'})</span>
        )}
      </span>
    </li>
  );
}

// Brittleness grid columns (importance × local variance). Same content/order as the hand-rolled
// table it replaced — DataTable adds zebra + sticky header + the mobile card list.
const brittlenessColumns: DataColumn<BrittlenessItem>[] = [
  { id: 'param', header: 'Parameter', align: 'left', mobileLabel: 'Parameter', render: (item) => item.param },
  {
    id: 'importance',
    header: 'Importance',
    align: 'right',
    mobileLabel: 'Importance',
    cellClassName: () => 'text-ay-muted',
    render: (item) => fmtNum(item.importance, 3),
  },
  {
    id: 'localVariance',
    header: 'Local variance',
    align: 'right',
    mobileLabel: 'Local variance',
    cellClassName: () => 'text-ay-muted',
    render: (item) => (item.localVariance == null ? '—' : fmtNum(item.localVariance, 4)),
  },
  {
    id: 'verdict',
    header: 'Verdict',
    align: 'right',
    mobileLabel: 'Verdict',
    render: (item) =>
      item.brittle ? (
        <span className="rounded px-1.5 py-0.5 text-[11px] font-medium text-warn ring-1 ring-warn/40">
          brittle
        </span>
      ) : (
        <span className="text-[11px] text-ay-muted">stable</span>
      ),
  },
];

interface InsightsViewProps {
  generations: Generation[];
  candidates: Candidate[];
}

export function InsightsView({ generations, candidates }: InsightsViewProps) {
  // Map each generation to the sweepJobId carried on its candidates (all a generation's candidates
  // share the one recorded sweep). Only generations with a resolvable sweep are pickable.
  const sweepByGen = useMemo(() => {
    const map = new Map<string, string>();
    for (const c of candidates) {
      if (c.sweepJobId && !map.has(c.generationId)) map.set(c.generationId, c.sweepJobId);
    }
    return map;
  }, [candidates]);

  const pickable = useMemo(
    () => generations.filter((g) => sweepByGen.has(g.id)).sort((a, b) => b.n - a.n),
    [generations, sweepByGen],
  );

  const [genId, setGenId] = useState<string>(() => pickable[0]?.id ?? '');
  const effectiveGenId = sweepByGen.has(genId) ? genId : (pickable[0]?.id ?? '');
  const sweepJobId = effectiveGenId ? sweepByGen.get(effectiveGenId) : undefined;
  const q = useSweepInsights(sweepJobId);

  if (pickable.length === 0) {
    return (
      <p className="rounded-md border border-ay-border bg-surface-1 px-4 py-8 text-center text-body-sm text-ay-muted">
        No recorded sweep yet — parameter-effect analytics appear once a generation's sweep is recorded.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <span className="text-caption text-ay-muted">Generation</span>
        <Select
          value={effectiveGenId}
          ariaLabel="Choose a generation for parameter insights"
          options={pickable.map((g) => ({ value: g.id, label: `Gen ${g.n}` }))}
          onChange={setGenId}
        />
      </div>

      <QueryState query={q} skeleton={<Skeleton variant="card" />} errorTitle="Couldn't load insights">
        {(data) => (
          <div className="flex flex-col gap-6">
            {data.notes.length > 0 && (
              <div className="flex items-start gap-2 rounded-md border border-ay-border bg-surface-2/40 px-3 py-2 text-[11px] text-ay-muted">
                <Lightbulb aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                <span>
                  {data.notes.map((n, i) => (
                    <span key={i} className="block">
                      {n}
                    </span>
                  ))}
                </span>
              </div>
            )}

            {/* Parameter-importance tornado (§3.4). */}
            <section aria-label="Parameter importance">
              <h3 className="mb-2 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                Parameter importance — |Spearman ρ| on {data.metric} ({data.direction})
              </h3>
              {data.importance.length === 0 ? (
                <p className="text-caption text-ay-muted">No varying parameter to rank.</p>
              ) : (
                (() => {
                  const max = Math.max(...data.importance.map((it) => it.score), 0.001);
                  return (
                    <ul className="flex flex-col gap-1.5">
                      {[...data.importance]
                        .sort((a, b) => b.score - a.score)
                        .map((it) => (
                          <TornadoRow key={it.param} item={it} max={max} />
                        ))}
                    </ul>
                  );
                })()
              )}
            </section>

            {/* Brittleness grid (§3.4 — importance × local variance). */}
            <section aria-label="Parameter brittleness">
              <h3 className="mb-2 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                Brittleness — high-importance params whose neighbours diverge
              </h3>
              {data.brittleness.length === 0 ? (
                <p className="text-caption text-ay-muted">No brittleness signal available.</p>
              ) : (
                <DataTable
                  columns={brittlenessColumns}
                  rows={data.brittleness}
                  rowKey={(b) => b.param}
                  rowClassName={(b) => (b.brittle ? 'bg-warn/5' : '')}
                  ariaLabel="Brittleness grid"
                />
              )}
            </section>

            {/* 2-D interaction slices (§3.4) — one compact grid per top pair. */}
            {data.slices.length > 0 && (
              <section aria-label="Interaction slices">
                <h3 className="mb-2 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                  Interaction slices (median {data.metric} per cell)
                </h3>
                <div className="flex flex-col gap-4">
                  {data.slices.map((slice) => (
                    <div key={`${slice.paramX}×${slice.paramY}`} className="overflow-x-auto">
                      <div className="mb-1 text-[11px] text-ay-muted">
                        {slice.paramX} × {slice.paramY}
                      </div>
                      <table className="text-[11px]">
                        <tbody>
                          {slice.cells.map((cell, i) => (
                            <tr key={i} className="border-t border-ay-border/40">
                              <td className="py-0.5 pr-2 tabular-nums text-ay-muted">{String(cell.x)}</td>
                              <td className="py-0.5 pr-2 tabular-nums text-ay-muted">{String(cell.y)}</td>
                              <td className="py-0.5 pr-2 text-right tabular-nums text-ay-text">
                                {fmtNum(cell.objective ?? null, 3)}
                              </td>
                              <td className="py-0.5 text-right text-ay-muted/70">n={cell.count}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ))}
                </div>
              </section>
            )}
          </div>
        )}
      </QueryState>
    </div>
  );
}
