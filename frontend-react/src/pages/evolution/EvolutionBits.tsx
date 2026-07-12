import { cn } from '../../lib/cn.ts';
import {
  fmtSigned,
  GATE_LABELS,
  isGateFail,
  isGatePass,
  robustScoreContribs,
  type GateResult,
  type Scorecard,
} from '../../api/evolution.ts';

// Shared presentational atoms for the /evolution surfaces (board / workspace / inbox). Every chip
// carries its meaning as TEXT (the status word / band letter), never colour alone; tone is a coloured
// RING over a neutral surface (the house SentimentBadge/InsightBits convention — a same-hue tint lifts
// the background luminance under coloured text below WCAG AA). One pill component, tone maps per enum.

function Pill({ tone, label, title }: { tone: string; label: string; title?: string }) {
  return (
    <span
      title={title}
      className={cn(
        'inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-medium ring-1',
        tone,
      )}
    >
      {label}
    </span>
  );
}

const POLICY_TONE: Record<string, string> = {
  SIM_FIRST: 'text-accent ring-accent/40',
  LIVE_FIRST: 'text-bull ring-bull/40',
  SIM_BLOCKED: 'text-warn ring-warn/40',
};

/** The campaign evidence-policy badge (design §1.2). */
export function PolicyBadge({ policy }: { policy: string }) {
  return <Pill tone={POLICY_TONE[policy] ?? 'text-ay-muted ring-ay-border'} label={policy} />;
}

const CAMPAIGN_STATUS_TONE: Record<string, string> = {
  ACTIVE: 'text-bull ring-bull/40',
  PAUSED: 'text-warn ring-warn/40',
  CLOSED: 'text-ay-muted ring-ay-border',
};

export function CampaignStatusBadge({ status }: { status: string }) {
  return <Pill tone={CAMPAIGN_STATUS_TONE[status] ?? 'text-ay-muted ring-ay-border'} label={status} />;
}

// Candidate state tone: the winning end of the lifecycle reads bull, terminal-negative reads bear,
// the live-accrual middle reads accent, the sim front reads muted.
const STATE_TONE: Record<string, string> = {
  PROPOSED: 'text-ay-muted ring-ay-border',
  EVALUATING: 'text-ay-muted ring-ay-border',
  SCORED: 'text-ay-text ring-ay-border',
  SURVIVOR: 'text-accent ring-accent/40',
  PAPER: 'text-accent ring-accent/40',
  TAKE_ELIGIBLE: 'text-bull ring-bull/40',
  PROMOTED: 'text-bull ring-bull/50',
  RETIRED: 'text-ay-muted ring-ay-border',
  ROLLED_BACK: 'text-bear ring-bear/40',
};

/** The §8.1 candidate state badge. */
export function StateBadge({ state }: { state: string | null | undefined }) {
  const s = state ?? 'PROPOSED';
  return <Pill tone={STATE_TONE[s] ?? 'text-ay-muted ring-ay-border'} label={s.replace(/_/g, ' ')} />;
}

const KIND_TONE: Record<string, string> = {
  PUBLISH_PAPER: 'text-accent ring-accent/40',
  PROMOTE: 'text-bull ring-bull/40',
  ROLLBACK: 'text-bear ring-bear/40',
  RETIRE: 'text-ay-muted ring-ay-border',
  REVIEW_GATE: 'text-warn ring-warn/40',
};

/** The proposal-kind badge. */
export function ProposalKindBadge({ kind }: { kind: string }) {
  return <Pill tone={KIND_TONE[kind] ?? 'text-ay-muted ring-ay-border'} label={kind.replace(/_/g, ' ')} />;
}

const PROPOSAL_STATUS_TONE: Record<string, string> = {
  PENDING: 'text-warn ring-warn/40',
  APPROVED: 'text-bull ring-bull/40',
  REJECTED: 'text-bear ring-bear/40',
  EXPIRED: 'text-ay-muted ring-ay-border',
};

export function ProposalStatusBadge({ status }: { status: string }) {
  return <Pill tone={PROPOSAL_STATUS_TONE[status] ?? 'text-ay-muted ring-ay-border'} label={status} />;
}

const VERDICT_TONE: Record<string, string> = {
  ALIGNED: 'text-bull ring-bull/40',
  PENALIZED: 'text-warn ring-warn/40',
  DIVERGENT: 'text-bear ring-bear/40',
  INSUFFICIENT: 'text-ay-muted ring-ay-border',
};

/** The §7.2 reconciliation-verdict badge. */
export function VerdictBadge({ verdict }: { verdict: string }) {
  return <Pill tone={VERDICT_TONE[verdict] ?? 'text-ay-muted ring-ay-border'} label={verdict} />;
}

// Gate status glyph — the meaning is the WORD (PASS/FAIL/…); the icon is aria-hidden reinforcement.
const GATE_TONE: Record<string, string> = {
  PASS: 'text-bull ring-bull/40',
  FAIL: 'text-bear ring-bear/40',
};
const GATE_NEUTRAL = 'text-ay-muted ring-ay-border';

/** A compact hard-gate chip: the gate name + its status word (green PASS / red FAIL / muted else). */
export function GateChip({ gate }: { gate: GateResult }) {
  const label = GATE_LABELS[gate.id] ?? gate.id;
  const tone = isGatePass(gate.status) || isGateFail(gate.status) ? GATE_TONE[gate.status] : GATE_NEUTRAL;
  const valueStr =
    gate.value == null
      ? ''
      : typeof gate.value === 'number'
        ? ` (${gate.value.toLocaleString('en-IN', { maximumFractionDigits: 3 })})`
        : ` (${String(gate.value)})`;
  return (
    <span
      title={gate.note ? `${label}: ${gate.note}` : `${label}${valueStr}`}
      className={cn('inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] ring-1', tone)}
    >
      <span className="font-medium">{label}</span>
      <span>{gate.status}</span>
    </span>
  );
}

/**
 * The RobustScore stacked contribution bar (design §10 candidate card: "component z × weight"). Each
 * component renders a magnitude bar proportional to |weight × z|, coloured by sign (bull positive /
 * bear negative) — the numeric contribution rides on the label so the row is never colour-only.
 */
export function RobustScoreBreakdown({ card }: { card: Scorecard | null | undefined }) {
  const contribs = robustScoreContribs(card);
  if (contribs.length === 0) {
    return <p className="text-caption text-ay-muted">No component breakdown on this scorecard.</p>;
  }
  const max = Math.max(...contribs.map((c) => Math.abs(c.contribution)), 0.001);
  return (
    <ul className="flex flex-col gap-1.5">
      {contribs.map((c) => {
        const pct = Math.min(100, (Math.abs(c.contribution) / max) * 100);
        const positive = c.contribution >= 0;
        return (
          <li key={c.id} className="grid grid-cols-[10rem_1fr_4.5rem] items-center gap-2 text-xs">
            <span className="truncate text-ay-text" title={`${c.label} — z ${fmtSigned(c.z)} × weight ${c.weight}`}>
              {c.label}
            </span>
            <span className="relative block h-3 overflow-hidden rounded bg-surface-2">
              <span
                aria-hidden="true"
                className={cn('absolute inset-y-0 rounded', positive ? 'left-1/2' : 'right-1/2')}
                style={{
                  width: `${pct / 2}%`,
                  background: positive
                    ? 'color-mix(in srgb, var(--ay-bull) 45%, transparent)'
                    : 'color-mix(in srgb, var(--ay-bear) 45%, transparent)',
                }}
              />
              <span aria-hidden="true" className="absolute inset-y-0 left-1/2 w-px bg-ay-border" />
            </span>
            <span className="text-right tabular-nums text-ay-text">{fmtSigned(c.contribution)}</span>
          </li>
        );
      })}
    </ul>
  );
}
