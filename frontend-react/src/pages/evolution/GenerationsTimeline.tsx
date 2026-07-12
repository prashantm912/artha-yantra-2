import { GitCommitVertical } from 'lucide-react';
import { fmtIstTime, shortId, type Candidate, type Generation } from '../../api/evolution.ts';

// The §10 Generations tab: the audit trail rendered as a timeline — one node per generation with its
// proposal pre-registration (§3.1 snooping ledger), the engine SHA + data epoch it ran under (the
// comparability provenance, audit P0-2), stress touches, status, and how many candidates it produced.

function DataEpochLabel({ epoch }: { epoch: Record<string, unknown> | null | undefined }) {
  if (!epoch) return <span className="text-ay-muted">—</span>;
  // The data epoch is a loose provenance dict (e.g. {dataHash, from, to}); show the most useful keys.
  const hash = typeof epoch.dataHash === 'string' ? epoch.dataHash : null;
  const from = typeof epoch.from === 'string' ? epoch.from.slice(0, 10) : null;
  const to = typeof epoch.to === 'string' ? epoch.to.slice(0, 10) : null;
  if (hash) return <span className="tabular-nums">{shortId(hash)}</span>;
  if (from || to) return <span className="tabular-nums">{from ?? '?'} → {to ?? '?'}</span>;
  return <span className="text-ay-muted">present</span>;
}

export function GenerationsTimeline({
  generations,
  candidates,
}: {
  generations: Generation[];
  candidates: Candidate[];
}) {
  if (generations.length === 0) {
    return (
      <p className="rounded-md border border-ay-border bg-surface-1 px-4 py-8 text-center text-body-sm text-ay-muted">
        No generations recorded yet.
      </p>
    );
  }
  const countByGen = new Map<string, number>();
  for (const c of candidates) countByGen.set(c.generationId, (countByGen.get(c.generationId) ?? 0) + 1);
  const ordered = [...generations].sort((a, b) => b.n - a.n);

  return (
    <ol className="flex flex-col gap-0">
      {ordered.map((g, i) => {
        const proposal = g.proposal ?? null;
        const preRegistered =
          proposal && typeof proposal === 'object'
            ? (proposal.note as string) ?? (proposal.summary as string) ?? JSON.stringify(proposal)
            : null;
        return (
          <li key={g.id} className="flex gap-3">
            {/* rail */}
            <div className="flex flex-col items-center">
              <GitCommitVertical aria-hidden="true" className="size-5 text-accent" />
              {i < ordered.length - 1 && <span aria-hidden="true" className="w-px flex-1 bg-ay-border" />}
            </div>
            <div className="flex-1 pb-5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-body font-semibold text-ay-text">Generation {g.n}</span>
                {g.status && (
                  <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-ay-muted">
                    {g.status}
                  </span>
                )}
                <span className="text-[11px] text-ay-muted">
                  {countByGen.get(g.id) ?? 0} candidate(s)
                </span>
              </div>
              <dl className="mt-1.5 grid grid-cols-2 gap-x-4 gap-y-1 text-[11px] sm:grid-cols-4">
                <div>
                  <dt className="text-ay-muted">Engine SHA</dt>
                  <dd className="tabular-nums text-ay-text">{shortId(g.engineSha)}</dd>
                </div>
                <div>
                  <dt className="text-ay-muted">Data epoch</dt>
                  <dd className="text-ay-text">
                    <DataEpochLabel epoch={g.dataEpoch} />
                  </dd>
                </div>
                <div>
                  <dt className="text-ay-muted">Stress touches</dt>
                  <dd className="tabular-nums text-ay-text">{g.stressTouches}</dd>
                </div>
                <div>
                  <dt className="text-ay-muted">Search hash</dt>
                  <dd className="tabular-nums text-ay-text">{shortId(g.searchSpaceHash)}</dd>
                </div>
                <div className="col-span-2 sm:col-span-4">
                  <dt className="sr-only">Timing</dt>
                  <dd className="text-ay-muted">
                    Started {fmtIstTime(g.startedAt)} · finished {fmtIstTime(g.finishedAt)}
                  </dd>
                </div>
              </dl>
              {preRegistered && (
                <p className="mt-1.5 rounded border-l-2 border-accent/50 bg-surface-1 px-2 py-1 text-[11px] text-ay-muted">
                  Pre-registered: {preRegistered}
                </p>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
