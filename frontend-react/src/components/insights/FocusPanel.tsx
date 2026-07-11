import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Radar } from 'lucide-react';
import { Skeleton } from '../Skeletons.tsx';
import { Button } from '../atoms/Button.tsx';
import {
  formatAge,
  insightBand,
  useAckInsight,
  useDismissInsight,
  useFocus,
  useInsightSummary,
  type Insight,
} from '../../api/insights.ts';
import { BandChip, EvidenceChip, SeverityBadge } from './InsightBits.tsx';
import { TrustChip } from './TrustChip.tsx';
import { InsightExplainDrawer } from './InsightExplainDrawer.tsx';

// The Focus panel (INT design §8.1) — the priority-ordered decision surface. Shadow mode (I1): this is
// an ADDITIVE dashboard panel that PREVIEWS what will replace the passive "Active Signals top-7"; the
// old card stays until the layer is trusted (§10.3 Stage 0). Top = the ranked signal queue
// (SIGNAL_PRIORITY, priority desc); below = the attention queue (everything else OPEN, severity-ordered).
// Each row: band chip · title · top-2 evidence · trust chip · age · Review/Ack/Dismiss. Take (PROPOSE →
// ticket) is I3 and intentionally absent. Reads only on mount/refetch — no WS (that is I3).

interface FocusItemProps {
  insight: Insight;
  onReview: (insight: Insight) => void;
  onAck: (id: string) => void;
  onDismiss: (id: string) => void;
  busy: boolean;
}

function FocusItem({ insight, onReview, onAck, onDismiss, busy }: FocusItemProps) {
  const evidence = (insight.evidence ?? []).slice(0, 2);
  return (
    <li className="rounded-md border border-ay-border bg-surface-1 p-2.5">
      <div className="flex items-start gap-2">
        <BandChip band={insightBand(insight)} className="mt-0.5 shrink-0" />
        <div className="min-w-0 flex-1">
          <button
            type="button"
            onClick={() => onReview(insight)}
            className="block text-left text-body-sm font-medium text-ay-text hover:text-accent"
          >
            {insight.title}
          </button>
          {evidence.length > 0 && (
            <div className="mt-1 flex flex-wrap gap-1">
              {evidence.map((ev, i) => (
                <EvidenceChip key={i} ev={ev} />
              ))}
            </div>
          )}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <span className="nums text-caption text-ay-muted">{formatAge(insight.generatedAt)}</span>
          <TrustChip state={insight.dataTrust} reasons={insight.trustReasons} />
        </div>
      </div>
      <div className="mt-2 flex items-center gap-1.5">
        <SeverityBadge severity={insight.severity} />
        <span className="ml-auto flex gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => onReview(insight)}>
            Review
          </Button>
          <Button variant="outline" size="sm" disabled={busy} onClick={() => onAck(insight.id)}>
            Ack
          </Button>
          <Button variant="outline" size="sm" disabled={busy} onClick={() => onDismiss(insight.id)}>
            Dismiss
          </Button>
        </span>
      </div>
    </li>
  );
}

export function FocusPanel() {
  const focus = useFocus();
  const summary = useInsightSummary();
  const ack = useAckInsight();
  const dismiss = useDismissInsight();
  const [selected, setSelected] = useState<Insight | null>(null);
  const [open, setOpen] = useState(false);

  const review = (insight: Insight) => {
    setSelected(insight);
    setOpen(true);
  };

  const data = focus.data;
  const signalQueue = data?.signalQueue ?? [];
  const attentionQueue = data?.attentionQueue ?? [];
  const suppressed = data?.suppressed ?? 0;
  const busy = ack.isPending || dismiss.isPending;

  const severityCounts = summary.data?.bySeverity ?? [];

  return (
    <section className="card shadow-e1" data-testid="focus-panel" aria-label="Focus">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h2 className="flex items-center gap-1.5 text-h3 text-ay-text">
          <Radar aria-hidden="true" className="size-4 text-ay-muted" />
          Focus
          <span
            className="rounded bg-surface-2 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-ay-muted"
            title="Shadow mode — an additive preview; the passive Active Signals card is still authoritative"
          >
            shadow
          </span>
        </h2>
        <div className="flex items-center gap-2">
          {severityCounts.length > 0 && (
            <span className="flex flex-wrap gap-1.5 text-caption text-ay-muted">
              {severityCounts.map((c) => (
                <span key={c.key}>
                  {c.count} {c.key.toLowerCase()}
                </span>
              ))}
            </span>
          )}
          <Link to="/insights" className="text-caption text-accent hover:underline">
            Open feed →
          </Link>
        </div>
      </div>

      {focus.isPending ? (
        <Skeleton variant="table-rows" />
      ) : focus.isError ? (
        <p role="alert" className="text-body-sm text-bear">
          Couldn't load the Focus queue.
        </p>
      ) : (
        <div className="flex flex-col gap-4">
          <div>
            <h3 className="mb-1.5 text-caption font-semibold uppercase tracking-wide text-ay-muted">
              Signal queue
            </h3>
            {signalQueue.length > 0 ? (
              <ul className="flex flex-col gap-2">
                {signalQueue.map((i) => (
                  <FocusItem
                    key={i.id}
                    insight={i}
                    onReview={review}
                    onAck={ack.mutate}
                    onDismiss={dismiss.mutate}
                    busy={busy}
                  />
                ))}
              </ul>
            ) : (
              <p className="text-body-sm text-ay-muted">
                No A/B signals right now
                {suppressed > 0 && (
                  <>
                    {' '}
                    — {suppressed} suppressed (
                    <Link to="/insights" className="text-accent hover:underline">
                      show
                    </Link>
                    )
                  </>
                )}
                .
              </p>
            )}
          </div>

          <div>
            <h3 className="mb-1.5 text-caption font-semibold uppercase tracking-wide text-ay-muted">
              Attention queue
            </h3>
            {attentionQueue.length > 0 ? (
              <ul className="flex flex-col gap-2">
                {attentionQueue.map((i) => (
                  <FocusItem
                    key={i.id}
                    insight={i}
                    onReview={review}
                    onAck={ack.mutate}
                    onDismiss={dismiss.mutate}
                    busy={busy}
                  />
                ))}
              </ul>
            ) : (
              <p className="text-body-sm text-ay-muted">Nothing needs attention.</p>
            )}
          </div>
        </div>
      )}

      <InsightExplainDrawer insight={selected} open={open} onOpenChange={setOpen} />
    </section>
  );
}
