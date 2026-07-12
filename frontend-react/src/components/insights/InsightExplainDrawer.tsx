import { useEffect, useState } from 'react';
import { AlertTriangle, Check, ThumbsDown, ThumbsUp, X } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { Button } from '../atoms/Button.tsx';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../ui/sheet.tsx';
import {
  insightBand,
  useAckInsight,
  useDismissInsight,
  useInsightFeedback,
  type Insight,
} from '../../api/insights.ts';
import { BandChip, EvidenceChip, SeverityBadge } from './InsightBits.tsx';
import { TrustChip } from './TrustChip.tsx';
import { InsightActions } from './InsightActions.tsx';

// The ONE explain drawer (INT design §8.3), reused by the Focus panel and the insight feed. Renders the
// §3.4 explain contract straight from the row (list/focus/get are self-contained, §9.4 — no extra
// fetch): the trust banner + reasons, the plain-language explanation, the priority component table
// (weight × c = points with the named evidence), the evidence list, the triage actions and the
// Useful/Not-useful feedback. I3 adds the tier-2 PROPOSE action row (<InsightActions>) — Take → ticket,
// Draft journal, Acknowledge sell, Mute type — trust-gated + routed through the EXISTING governed
// endpoints; ack/dismiss/feedback stay the drawer's own triage.

const num = (v: number | undefined | null, dp = 2): string =>
  v == null || Number.isNaN(v) ? '—' : v.toLocaleString('en-IN', { maximumFractionDigits: dp });

function fmtTime(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso));
}

interface InsightExplainDrawerProps {
  insight: Insight | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function InsightExplainDrawer({ insight, open, onOpenChange }: InsightExplainDrawerProps) {
  const ack = useAckInsight();
  const dismiss = useDismissInsight();
  const feedback = useInsightFeedback();
  const [feedbackGiven, setFeedbackGiven] = useState<string | null>(null);

  // Reset the transient "thanks" state whenever a different insight is opened into the drawer.
  useEffect(() => {
    setFeedbackGiven(null);
  }, [insight?.id]);

  return (
    // Gate on `insight` too: radix Dialog.Content mounts only when open, and it requires a Title for its
    // accessible name — never let it mount an empty (insight-less) content (a11y guard).
    <Sheet open={open && insight != null} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 overflow-y-auto border-ay-border bg-surface-1 sm:max-w-lg"
      >
        {insight && (
          <>
            <SheetHeader className="border-b border-ay-border">
              <div className="flex flex-wrap items-center gap-2">
                <BandChip band={insightBand(insight)} />
                <SeverityBadge severity={insight.severity} />
                <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-ay-muted">
                  {insight.type}
                </span>
                <TrustChip state={insight.dataTrust} reasons={insight.trustReasons} />
              </div>
              <SheetTitle className="text-h3 text-ay-text">{insight.title}</SheetTitle>
              <SheetDescription className="text-caption text-ay-muted">
                {insight.scope} · {fmtTime(insight.generatedAt)} IST · {insight.status}
              </SheetDescription>
            </SheetHeader>

            <div className="flex flex-col gap-5 p-4">
              {/* Trust banner — only when data trust is not clean; carries the reasons (§8.3). */}
              {insight.dataTrust !== 'OK' && (
                <div
                  className={cn(
                    'flex items-start gap-2 rounded-md border px-3 py-2 text-body-sm',
                    insight.dataTrust === 'BLOCKED'
                      ? 'border-bear/40 text-bear'
                      : 'border-warn/40 text-warn',
                  )}
                >
                  <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                  <div>
                    <div className="font-medium">
                      Data trust: {insight.dataTrust}
                      {insight.dataTrust === 'BLOCKED' && ' — advice suppressed'}
                    </div>
                    {insight.trustReasons && insight.trustReasons.length > 0 && (
                      <ul className="mt-0.5 list-disc pl-4 text-ay-muted">
                        {insight.trustReasons.map((r, i) => (
                          <li key={i}>{r}</li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
              )}

              {/* Plain-language explanation (§4.3 invariant 1). */}
              <p className="text-body-sm text-ay-text">{insight.explanation}</p>

              {/* Priority component table — only when the insight is scored (§3.4). */}
              {insight.priorityDetail && (
                <section aria-label="Priority breakdown">
                  <h3 className="mb-1 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                    Priority {num(insight.priorityDetail.score)} ({insight.priorityDetail.band}) · trust
                    cap ×{num(insight.priorityDetail.trustCap)}
                  </h3>
                  <table className="w-full text-xs">
                    <thead className="text-left text-ay-muted">
                      <tr>
                        <th className="py-1 pr-2 font-medium">Component</th>
                        <th className="py-1 pr-2 text-right font-medium">Weight</th>
                        <th className="py-1 pr-2 text-right font-medium">Score</th>
                        <th className="py-1 text-right font-medium">Points</th>
                      </tr>
                    </thead>
                    <tbody>
                      {insight.priorityDetail.components.map((comp) => (
                        <tr key={comp.key} className="border-t border-ay-border/40 align-top">
                          <td className="py-1.5 pr-2">
                            <div className="text-ay-text">{comp.key}</div>
                            {comp.evidence && comp.evidence.length > 0 && (
                              <div className="mt-1 flex flex-wrap gap-1">
                                {comp.evidence.map((ev, i) => (
                                  <EvidenceChip key={i} ev={ev} />
                                ))}
                              </div>
                            )}
                          </td>
                          <td className="py-1.5 pr-2 text-right tabular-nums text-ay-muted">
                            {num(comp.weight)}
                          </td>
                          <td className="py-1.5 pr-2 text-right tabular-nums text-ay-muted">
                            {num(comp.c)}
                          </td>
                          <td className="py-1.5 text-right tabular-nums text-ay-text">
                            {num(comp.points)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </section>
              )}

              {/* Evidence list — every claim's source, "trust but verify" (§8.3 / §8.7). */}
              {insight.evidence && insight.evidence.length > 0 && (
                <section aria-label="Evidence">
                  <h3 className="mb-1 text-caption font-semibold uppercase tracking-wide text-ay-muted">
                    Evidence
                  </h3>
                  <ul className="flex flex-col gap-1.5">
                    {insight.evidence.map((ev, i) => (
                      <li key={i} className="text-xs">
                        <div className="flex flex-wrap items-baseline gap-x-2">
                          <span className="text-ay-muted">{ev.label}</span>
                          <span className="tabular-nums text-ay-text">{ev.value}</span>
                        </div>
                        {(ev.source?.endpoint || ev.source?.asOf) && (
                          <div className="text-[11px] text-ay-muted/80">
                            {ev.source?.endpoint && (
                              <code className="text-ay-muted">{ev.source.endpoint}</code>
                            )}
                            {ev.source?.asOf && <span> · as of {ev.source.asOf}</span>}
                          </div>
                        )}
                      </li>
                    ))}
                  </ul>
                </section>
              )}

              {/* Actions — triage (ack/dismiss) + Useful/Not-useful feedback (shadow-mode set). */}
              <section aria-label="Actions" className="flex flex-col gap-3 border-t border-ay-border pt-4">
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    icon={Check}
                    loading={ack.isPending}
                    disabled={insight.status !== 'OPEN'}
                    onClick={() => ack.mutate(insight.id, { onSuccess: () => onOpenChange(false) })}
                  >
                    Acknowledge
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    icon={X}
                    loading={dismiss.isPending}
                    disabled={insight.status !== 'OPEN'}
                    onClick={() => dismiss.mutate(insight.id, { onSuccess: () => onOpenChange(false) })}
                  >
                    Dismiss
                  </Button>
                </div>
                {/* Tier-2 PROPOSE actions (I3) — one-click to an EXISTING governed endpoint (ticket
                    prefill / journal draft / sell-ack / mute); trust-gated + 422-aware (§1.2 / §8.3). */}
                <InsightActions insight={insight} onActed={() => onOpenChange(false)} />
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-caption text-ay-muted">Was this useful?</span>
                  {feedbackGiven ? (
                    <span className="inline-flex items-center gap-1 text-caption text-bull">
                      <Check aria-hidden="true" className="size-3.5" /> Recorded ({feedbackGiven})
                    </span>
                  ) : (
                    <>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={ThumbsUp}
                        loading={feedback.isPending}
                        onClick={() =>
                          feedback.mutate(
                            { id: insight.id, verdict: 'USEFUL' },
                            { onSuccess: () => setFeedbackGiven('useful') },
                          )
                        }
                      >
                        Useful
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        icon={ThumbsDown}
                        loading={feedback.isPending}
                        onClick={() =>
                          feedback.mutate(
                            { id: insight.id, verdict: 'NOT_USEFUL' },
                            { onSuccess: () => setFeedbackGiven('not useful') },
                          )
                        }
                      >
                        Not useful
                      </Button>
                    </>
                  )}
                </div>
              </section>
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
