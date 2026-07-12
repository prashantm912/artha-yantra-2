import { ChevronRight } from 'lucide-react';
import { pipelineCounts, type Candidate } from '../../api/evolution.ts';

// The §10 item-4 promotion pipeline strip: a left-to-right kanban of the §8.1 lifecycle stages
// (PROPOSED → … → PROMOTED) with a per-stage candidate count, so "what is close to live" is one
// glance. The two terminal branches (RETIRED / ROLLED_BACK) render as a muted tail rather than a
// forward column — they are exits, not steps toward live.

const STAGE_LABEL: Record<string, string> = {
  PROPOSED: 'Proposed',
  EVALUATING: 'Evaluating',
  SCORED: 'Scored',
  SURVIVOR: 'Survivor',
  PAPER: 'Paper',
  TAKE_ELIGIBLE: 'Take-eligible',
  PROMOTED: 'Promoted',
};

export function PipelineStrip({ candidates }: { candidates: Candidate[] }) {
  const { stages, retired, rolledBack } = pipelineCounts(candidates);
  return (
    <section aria-label="Promotion pipeline" className="overflow-x-auto">
      <ol className="flex min-w-max items-stretch gap-1">
        {stages.map((s, i) => (
          <li key={s.stage} className="flex items-center gap-1">
            <div
              className="flex min-w-[5.5rem] flex-col items-center rounded-md border border-ay-border bg-surface-1 px-2.5 py-2 text-center"
              title={`${s.count} candidate(s) in ${STAGE_LABEL[s.stage]}`}
            >
              <span className="text-h4 tabular-nums text-ay-text">{s.count}</span>
              <span className="mt-0.5 text-[10px] uppercase tracking-wide text-ay-muted">
                {STAGE_LABEL[s.stage]}
              </span>
            </div>
            {i < stages.length - 1 && (
              <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-ay-muted/60" />
            )}
          </li>
        ))}
        {(retired > 0 || rolledBack > 0) && (
          <li className="ml-2 flex items-center gap-1 self-center border-l border-ay-border pl-3 text-xs text-ay-muted">
            {retired > 0 && (
              <span title="Candidates auto-retired (failed gates / dominated)">
                {retired} retired
              </span>
            )}
            {retired > 0 && rolledBack > 0 && <span aria-hidden="true">·</span>}
            {rolledBack > 0 && (
              <span title="Promoted versions rolled back">{rolledBack} rolled back</span>
            )}
          </li>
        )}
      </ol>
    </section>
  );
}
