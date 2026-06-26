import { useEffect, useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  usePublish,
  useRollback,
  useStrategyDetail,
  useStrategyDiff,
  useStrategyVersions,
  useStressWindow,
} from '../../api/strategies.ts';

// /strategies/:id/versions (master plan §20 parity, E-11 screen 6): the immutable version timeline,
// the structured diff (path: before → after) above a side-by-side YAML view (React has no Monaco —
// the diff endpoint's structured ops carry the semantic change), a publish dialog with the advisory
// CLEAN stress window (publish never blocked, E-13), and rollback (copy-forward draft).

function statusTone(status: string): string {
  if (status === 'published') return 'text-bull ring-bull/40';
  if (status === 'archived') return 'text-ay-muted ring-ay-border';
  return 'text-warn ring-warn/40';
}

export function StrategyVersionsPage() {
  const { id = '' } = useParams();
  const detail = useStrategyDetail(id);
  const versions = useStrategyVersions(id);
  const publish = usePublish(id);
  const rollback = useRollback(id);

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [publishOpen, setPublishOpen] = useState(false);
  const [notes, setNotes] = useState('');

  const rows = useMemo(() => versions.data?.items ?? [], [versions.data]);
  const versionList = useMemo(() => rows.map((v) => v.version), [rows]);
  const draftVersion = useMemo(() => rows.find((v) => v.status === 'draft')?.version ?? null, [rows]);

  // default compare = the two latest versions, once they arrive
  useEffect(() => {
    if (rows.length >= 1 && !to) {
      setTo(rows[0].version);
      setFrom((rows[1] ?? rows[0]).version);
    }
  }, [rows, to]);

  const diff = useStrategyDiff(id, from, to);
  const stress = useStressWindow(id, publishOpen);

  const doPublish = () => {
    publish.mutate(
      { targetVersion: draftVersion ?? undefined, notes },
      { onSuccess: () => setPublishOpen(false) },
    );
  };

  const doRollback = (version: string) => {
    if (window.confirm(`Roll back to v${version}? This creates a copy-forward draft.`)) {
      rollback.mutate({ version });
    }
  };

  return (
    <LoadBeat>
      <PageHeader
        title="Strategy versions"
        help="The immutable version history of this strategy; compare any two versions, publish a draft to the live engine, or roll back to an earlier one."
      />
      <div className="mb-3 flex items-center gap-2">
        <strong>{detail.data?.name ?? 'Strategy'} — versions</strong>
        <div className="flex-1" />
        <button
          type="button"
          onClick={() => setPublishOpen(true)}
          disabled={!draftVersion}
          title="Publish the current draft version to the live signal engine (needs a draft to publish)."
          className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
        >
          Publish…
        </button>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(20rem,28rem)_minmax(0,1fr)]">
        <section className="min-w-0">
          <QueryState
            query={versions}
            isEmpty={() => rows.length === 0}
            empty={{ title: 'No versions.' }}
            errorTitle="Couldn't load versions"
            skeleton={<Skeleton variant="table-rows" rows={5} cols={5} />}
          >
            {() => (
          <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                <tr>
                  <th className="px-2 py-2 font-medium">Version</th>
                  <th className="px-2 py-2 font-medium">Status</th>
                  <th className="px-2 py-2 font-medium">Checksum</th>
                  <th className="px-2 py-2 font-medium">Created</th>
                  <th className="px-2 py-2"><span className="ay-sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.version} className="border-t border-ay-border">
                    <td className="px-2 py-2 tabular-nums">{v.version}</td>
                    <td className="px-2 py-2">
                      <span className={cn('rounded px-1.5 py-0.5 text-xs font-semibold ring-1', statusTone(v.status))}>
                        {v.status}
                      </span>
                    </td>
                    <td className="px-2 py-2 font-mono text-xs">{v.checksum?.slice(0, 10)}</td>
                    <td className="px-2 py-2 tabular-nums">{v.createdAt ? v.createdAt.slice(0, 10) : '—'}</td>
                    <td className="px-2 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => setFrom(v.version)}
                        className="px-1.5 text-xs text-accent hover:underline"
                      >
                        Diff
                      </button>
                      <button
                        type="button"
                        onClick={() => doRollback(v.version)}
                        className="px-1.5 text-xs text-warn hover:underline"
                      >
                        Rollback
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </BeatBlock>
            )}
          </QueryState>
        </section>

        <section className="min-w-0">
          <div className="mb-2 flex items-center gap-2 text-sm">
            <span>Compare</span>
            <Select value={from || null} options={versionList} onChange={setFrom} ariaLabel="Diff from version" placeholder="from" title="The earlier version to compare from." />
            <span>→</span>
            <Select value={to || null} options={versionList} onChange={setTo} ariaLabel="Diff to version" placeholder="to" title="The later version to compare to." />
          </div>
          {diff.data ? (
            <>
              <div className="mb-2 max-h-36 overflow-auto rounded-lg border border-ay-border p-2 text-[0.82rem]">
                {diff.data.structured.length > 0 ? (
                  diff.data.structured.map((op) => (
                    <div key={op.path}>
                      <span className="font-mono">{op.path}</span>: {op.before ?? '∅'} → {op.after ?? '∅'}
                    </div>
                  ))
                ) : (
                  <div className="text-ay-muted">No structural differences.</div>
                )}
              </div>
              <div className="grid grid-cols-2 gap-2">
                <pre className="max-h-96 overflow-auto rounded-lg border border-ay-border bg-surface-1 p-2 font-mono text-xs">
                  {diff.data.yamlFrom}
                </pre>
                <pre className="max-h-96 overflow-auto rounded-lg border border-ay-border bg-surface-1 p-2 font-mono text-xs">
                  {diff.data.yamlTo}
                </pre>
              </div>
            </>
          ) : (
            <p className="text-sm text-ay-muted">Pick two versions to compare.</p>
          )}
        </section>
      </div>

      {publishOpen && (
        <div className="fixed inset-0 z-30 grid place-items-center bg-black/40 p-4">
          <div className="w-full max-w-xl rounded-lg border border-ay-border bg-surface-1 p-4 shadow-xl">
            <h2 className="mb-2 text-base font-semibold">Publish version</h2>
            <p className="mb-3 text-sm">
              This hot-swaps the live signal engine to <strong>v{draftVersion}</strong>.
            </p>
            <input
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Publish notes"
              aria-label="Publish notes"
              title="Optional note recorded with this publish (e.g. what changed)."
              className="mb-3 w-full rounded-md border border-ay-border bg-surface-1 px-3 py-2 text-sm text-ay-text"
            />
            <div className="mb-4 rounded-md border border-ay-border bg-surface-2 p-2 text-xs text-ay-muted">
              {stress.data
                ? `Advisory: a clean stress window is available ${stress.data.from.slice(0, 10)} → ${stress.data.to.slice(0, 10)} (publish is not blocked).`
                : 'Stress-window advisory loading… (publish is not blocked).'}
            </div>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setPublishOpen(false)}
                className="h-9 rounded-md border border-ay-border px-4 text-sm hover:border-accent"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={doPublish}
                disabled={publish.isPending}
                title="Confirm and hot-swap the live signal engine to this draft version."
                className="h-9 rounded-md bg-accent px-4 text-sm font-medium text-surface-0 hover:opacity-90 disabled:opacity-50"
              >
                Publish
              </button>
            </div>
          </div>
        </div>
      )}
    </LoadBeat>
  );
}
