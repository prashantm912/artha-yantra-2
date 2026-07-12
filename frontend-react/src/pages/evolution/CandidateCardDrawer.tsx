import { Link } from 'react-router-dom';
import { AlertTriangle, ExternalLink } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet.tsx';
import {
  fmtNum,
  fmtSigned,
  gateSummary,
  useReconciliations,
  type Candidate,
  type Reconciliation,
} from '../../api/evolution.ts';
import { GateChip, RobustScoreBreakdown, StateBadge, VerdictBadge } from './EvolutionBits.tsx';

// The candidate card (design §10 "the core artifact"), a right-side drawer reusing the InsightExplain
// pattern. Renders straight from the candidate's embedded §6.3 scorecard — params, the hard-gate
// checklist with values, the RobustScore component breakdown, penalties/flags/caveats, the §7.2
// live-gap tile + the version's reconciliation history, the holdout tile, and the drill-down links
// (sweep detail / run results). No extra fetch except the reconciliation list (only when the
// candidate carries a lived versionId). Summary → trade-level evidence in ≤ 3 clicks (§10).

function KeyVal({ k, v }: { k: string; v: unknown }) {
  const str =
    v == null
      ? '—'
      : typeof v === 'number'
        ? v.toLocaleString('en-IN', { maximumFractionDigits: 4 })
        : typeof v === 'object'
          ? JSON.stringify(v)
          : String(v);
  return (
    <div className="flex items-baseline justify-between gap-2 border-t border-ay-border/40 py-1 text-xs first:border-t-0">
      <span className="truncate text-ay-muted" title={k}>
        {k}
      </span>
      <span className="tabular-nums text-ay-text">{str}</span>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section aria-label={title}>
      <h3 className="mb-1.5 text-caption font-semibold uppercase tracking-wide text-ay-muted">{title}</h3>
      {children}
    </section>
  );
}

/** The §7.2 live-gap tile, rendered from the scorecard.liveGap dict (loose — the backend passes it
 *  through verbatim). Shows the verdict + gapZ + the DIVERGENT diagnosis checklist when present. */
function LiveGapTile({ liveGap }: { liveGap: Record<string, unknown> }) {
  const verdict = typeof liveGap.verdict === 'string' ? liveGap.verdict : 'INSUFFICIENT';
  const gapZ = typeof liveGap.gapZ === 'number' ? liveGap.gapZ : null;
  const counted = liveGap.counted === true;
  const diagnosis = Array.isArray(liveGap.diagnosis) ? (liveGap.diagnosis as unknown[]) : null;
  return (
    <div className="rounded-md border border-ay-border bg-surface-1 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <VerdictBadge verdict={verdict} />
        <span className="text-xs text-ay-muted">
          gapZ <span className="tabular-nums text-ay-text">{fmtSigned(gapZ)}</span>
        </span>
        {!counted && <span className="text-[11px] text-ay-muted">(not counted in score)</span>}
      </div>
      {diagnosis && diagnosis.length > 0 && (
        <div className="mt-2 flex items-start gap-1.5 text-[11px] text-bear">
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <ul className="list-disc pl-4 text-ay-muted">
            {diagnosis.map((d, i) => (
              <li key={i}>{typeof d === 'string' ? d : JSON.stringify(d)}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function ReconRow({ r }: { r: Reconciliation }) {
  return (
    <tr className="border-t border-ay-border/40">
      <td className="py-1 pr-2">
        <VerdictBadge verdict={r.verdict} />
      </td>
      <td className="py-1 pr-2 text-right tabular-nums text-ay-text">{fmtSigned(r.gapZ)}</td>
      <td className="py-1 pr-2 text-right tabular-nums text-ay-muted">{r.pairedTrades}</td>
      <td className="py-1 text-right text-[11px] text-ay-muted">
        {r.windowFrom?.slice(0, 10) ?? '—'} → {r.windowTo?.slice(0, 10) ?? '—'}
      </td>
    </tr>
  );
}

interface CandidateCardDrawerProps {
  candidate: Candidate | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CandidateCardDrawer({ candidate, open, onOpenChange }: CandidateCardDrawerProps) {
  const versionId = candidate?.versionId ?? null;
  const recons = useReconciliations(open ? versionId : null);
  const card = candidate?.scorecard ?? null;
  const gates = card?.gates ?? [];
  const summary = gateSummary(gates);
  const params = candidate?.params ?? {};
  const paramKeys = Object.keys(params);
  const reconItems = recons.data?.items ?? [];

  return (
    <Sheet open={open && candidate != null} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="w-full gap-0 overflow-y-auto border-ay-border bg-surface-1 sm:max-w-xl"
      >
        {candidate && (
          <>
            <SheetHeader className="border-b border-ay-border">
              <div className="flex flex-wrap items-center gap-2">
                <StateBadge state={candidate.state} />
                {candidate.mutationKind && (
                  <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-ay-muted">
                    {candidate.mutationKind}
                  </span>
                )}
                {card?.rank != null && (
                  <span className="text-[11px] text-ay-muted">rank #{card.rank}</span>
                )}
                {card?.rankable === false && (
                  <span className="text-[11px] text-warn" title="Failed a hard gate — not rankable">
                    not rankable
                  </span>
                )}
              </div>
              <SheetTitle className="flex items-baseline gap-2 text-h3 text-ay-text">
                RobustScore
                <span className="tabular-nums">{fmtNum(card?.robustScore, 3)}</span>
                {card?.comparator?.delta != null && (
                  <span
                    className={cn(
                      'text-body-sm tabular-nums',
                      card.comparator.delta >= 0 ? 'text-bull' : 'text-bear',
                    )}
                    title="Δ vs champion"
                  >
                    {fmtSigned(card.comparator.delta)} vs champion
                  </span>
                )}
              </SheetTitle>
              <SheetDescription className="text-caption text-ay-muted">
                {summary.passed}/{summary.total} gates green · candidate {candidate.id}
              </SheetDescription>
            </SheetHeader>

            <div className="flex flex-col gap-5 p-4">
              {/* Hard-gate checklist with values — the FAIL rows carry the reason (§6.1). */}
              <Section title={`Hard gates (${summary.passed}/${summary.total} green)`}>
                {gates.length === 0 ? (
                  <p className="text-caption text-ay-muted">No gate results on this scorecard.</p>
                ) : (
                  <div className="flex flex-wrap gap-1.5">
                    {gates.map((g) => (
                      <GateChip key={g.id} gate={g} />
                    ))}
                  </div>
                )}
                {summary.failed > 0 && (
                  <p className="mt-1.5 text-[11px] text-bear">
                    Blocked by: {summary.failedIds.join(', ')}
                  </p>
                )}
              </Section>

              {/* RobustScore component × weight stacked breakdown (§6.2). */}
              <Section title="RobustScore breakdown">
                <RobustScoreBreakdown card={card} />
                {card?.penalties && (
                  <p className="mt-2 text-[11px] text-ay-muted">
                    Penalties — DOF <span className="tabular-nums text-ay-text">−{fmtNum(card.penalties.dof)}</span>
                    {' · '}caveats <span className="tabular-nums text-ay-text">−{fmtNum(card.penalties.caveats)}</span>
                  </p>
                )}
              </Section>

              {/* Params (the tuned knob-set — §10 params-diff surface). */}
              <Section title={`Parameters (${paramKeys.length})`}>
                {paramKeys.length === 0 ? (
                  <p className="text-caption text-ay-muted">No parameter vector recorded.</p>
                ) : (
                  <div className="rounded-md border border-ay-border bg-surface-1 px-3 py-1.5">
                    {paramKeys.map((k) => (
                      <KeyVal key={k} k={k} v={params[k]} />
                    ))}
                  </div>
                )}
              </Section>

              {/* Flags + caveats (§6.3). */}
              {((card?.flags?.length ?? 0) > 0 || (card?.caveats?.length ?? 0) > 0) && (
                <Section title="Flags & caveats">
                  <ul className="flex flex-col gap-1 text-[11px]">
                    {card?.flags?.map((f, i) => (
                      <li key={`f${i}`} className="flex items-start gap-1.5 text-warn">
                        <AlertTriangle aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                        <span>{f}</span>
                      </li>
                    ))}
                    {card?.caveats?.map((c, i) => (
                      <li key={`c${i}`} className="text-ay-muted">
                        · {c}
                      </li>
                    ))}
                  </ul>
                </Section>
              )}

              {/* §7.2 live-gap tile + the version's reconciliation history (only with live evidence). */}
              {(card?.liveGap || versionId) && (
                <Section title="Live-gap (backtest vs paper)">
                  {card?.liveGap ? (
                    <LiveGapTile liveGap={card.liveGap} />
                  ) : (
                    <p className="text-caption text-ay-muted">No live-gap panel on this scorecard yet.</p>
                  )}
                  {versionId && reconItems.length > 0 && (
                    <div className="mt-2 overflow-x-auto">
                      <table className="w-full text-xs">
                        <thead className="text-left text-ay-muted">
                          <tr>
                            <th className="py-1 pr-2 font-medium">Verdict</th>
                            <th className="py-1 pr-2 text-right font-medium">gapZ</th>
                            <th className="py-1 pr-2 text-right font-medium">Pairs</th>
                            <th className="py-1 text-right font-medium">Window</th>
                          </tr>
                        </thead>
                        <tbody>
                          {reconItems.map((r) => (
                            <ReconRow key={r.id} r={r} />
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </Section>
              )}

              {/* Holdout tile (§6.1 holdout gate + evidence.holdoutRun). */}
              <Section title="Holdout">
                {candidate.holdoutRunId || card?.evidence?.holdoutRun ? (
                  <div className="flex items-center gap-2 text-xs">
                    <span className="text-bull">consumed</span>
                    <Link
                      to={`/backtests/${candidate.holdoutRunId ?? card?.evidence?.holdoutRun}`}
                      className="inline-flex items-center gap-1 text-accent hover:underline"
                    >
                      run results <ExternalLink aria-hidden="true" className="size-3" />
                    </Link>
                  </div>
                ) : (
                  <p className="text-caption text-ay-muted">Holdout not yet consumed.</p>
                )}
              </Section>

              {/* Drill-down links (§10 — summary to trade-level evidence). */}
              <section aria-label="Open in detail" className="flex flex-wrap gap-2 border-t border-ay-border pt-4">
                {candidate.sweepJobId && (
                  <Link
                    to={`/optimizations/${candidate.sweepJobId}`}
                    className="inline-flex h-8 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2.5 text-body-sm text-ay-text hover:border-accent"
                  >
                    Sweep detail <ExternalLink aria-hidden="true" className="size-3.5" />
                  </Link>
                )}
                {card?.runId && (
                  <Link
                    to={`/backtests/${card.runId}`}
                    className="inline-flex h-8 items-center gap-1.5 rounded-md border border-ay-border bg-surface-1 px-2.5 text-body-sm text-ay-text hover:border-accent"
                  >
                    Run results <ExternalLink aria-hidden="true" className="size-3.5" />
                  </Link>
                )}
              </section>
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
