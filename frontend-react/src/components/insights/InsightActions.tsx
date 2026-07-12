import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { BellOff, FileText, Receipt, ShieldCheck } from 'lucide-react';
import { Button } from '../atoms/Button.tsx';
import { apiFetch, ApiError } from '../../api/client.ts';
import {
  actionGate,
  insightActions,
  useAct,
  type ActionSpec,
  type ActResponse,
  type Insight,
  type JournalDraft,
  type ProposeAction,
  type TicketPrefill,
} from '../../api/insights.ts';

// The I3 PROPOSE action row (INT design §1.2 / §8.3 / §8.6). Renders the one-click PROPOSE buttons that
// apply to an insight — Take → ticket, Draft journal, Acknowledge sell, Mute type — each of which calls
// /act. It NEVER places an order or writes the journal itself: a PREFILL response routes to the EXISTING
// governed UI (the /orders paper ticket, the /journal new-entry form) with the fields pre-seeded, so the
// manual-checks gate + risk governor + XSRF ride the real mutation path; a PROPOSED response is completed
// against the existing endpoint; DONE (mute) is an in-module idempotent write. Trust-gating is enforced
// client-side (§7.4): a BLOCKED insight disables ALL actions, a DEGRADED insight disables the order
// prefills — with the reason on the disabled button — and the 422 the backend still returns on a raced
// gate change is surfaced inline (never swallowed).

const ICONS: Partial<Record<ProposeAction, typeof Receipt>> = {
  OPEN_TICKET: Receipt,
  JOURNAL_DRAFT_ACCEPT: FileText,
  ACK_SELL_DECISION: ShieldCheck,
  MUTE_TYPE: BellOff,
};

/** The /orders deep-link the pre-filled paper ticket reads (OrderPrefillTicket param grammar). */
function ticketUrl(t: TicketPrefill): string {
  const p = new URLSearchParams();
  p.set('sig', String(t.signalId));
  p.set('symbol', `${t.exchange ?? ''}:${t.tradingsymbol ?? ''}`);
  if (t.side) p.set('side', t.side);
  if (t.qty != null) p.set('qty', String(t.qty));
  if (t.stopLoss != null) p.set('sl', String(t.stopLoss));
  if (t.target != null) p.set('tp', String(t.target));
  return `/orders?${p.toString()}`;
}

/** The /journal deep-link the draft-accept entry point reads (pre-seeds the new-entry form). */
function journalUrl(d: JournalDraft): string {
  const p = new URLSearchParams();
  if (d.signalId != null) p.set('draftSignalId', String(d.signalId));
  if (d.note) p.set('draftNote', d.note);
  if (d.tags && d.tags.length > 0) p.set('draftTags', d.tags.join(','));
  return `/journal?${p.toString()}`;
}

function errText(err: unknown): string {
  return err instanceof ApiError ? err.message : 'Action failed — try again.';
}

interface InsightActionsProps {
  insight: Insight;
  /** Restrict to a subset of the applicable actions (the Focus row shows only Take → ticket). */
  only?: ProposeAction[];
  size?: 'sm' | 'md';
  /** Called after a DONE / prefill-navigation completes — the drawer/panel closes on this. */
  onActed?: () => void;
}

/** The PROPOSE action buttons for one insight (tier-2; ack/dismiss stay on the drawer triage row). */
export function InsightActions({ insight, only, size = 'sm', onActed }: InsightActionsProps) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const act = useAct();
  // ACK_SELL_DECISION returns a PROPOSED instruction the browser completes against the EXISTING
  // sell-decision acknowledge endpoint (a governed swing endpoint — XSRF rides via apiFetch).
  const completeSellAck = useMutation({
    mutationFn: (sellId: number) =>
      apiFetch(`/signals/sell-decisions/${sellId}/ack`, { method: 'POST', json: {}, silenceToast: true }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['insights'] }),
  });
  const [error, setError] = useState<string | null>(null);

  const specs = insightActions(insight).filter((s) => !only || only.includes(s.action));
  if (specs.length === 0) return null;

  const routeResult = (res: ActResponse) => {
    if (res.status === 'PREFILL' && res.ticket) {
      navigate(ticketUrl(res.ticket));
      onActed?.();
      return;
    }
    if (res.status === 'PREFILL' && res.journal) {
      navigate(journalUrl(res.journal));
      onActed?.();
      return;
    }
    if (res.status === 'PROPOSED' && res.action === 'ACK_SELL_DECISION' && res.sellDecisionId != null) {
      completeSellAck.mutate(res.sellDecisionId, {
        onSuccess: () => onActed?.(),
        onError: (e) => setError(errText(e)),
      });
      return;
    }
    // DONE (mute) or a PROPOSED with no browser follow-through needed.
    onActed?.();
  };

  const run = (spec: ActionSpec) => {
    setError(null);
    act.mutate(
      { id: insight.id, action: spec.action },
      { onSuccess: routeResult, onError: (e) => setError(errText(e)) },
    );
  };

  const busy = act.isPending || completeSellAck.isPending;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap gap-2">
        {specs.map((spec) => {
          const gated = actionGate(insight, spec);
          const Icon = ICONS[spec.action];
          return (
            <Button
              key={spec.action}
              variant="outline"
              size={size}
              icon={Icon}
              disabled={gated != null || busy}
              loading={busy && act.variables?.action === spec.action}
              title={gated ?? undefined}
              onClick={() => run(spec)}
            >
              {spec.label}
            </Button>
          );
        })}
      </div>
      {error && (
        <p role="alert" className="text-caption text-bear">
          {error}
        </p>
      )}
    </div>
  );
}
