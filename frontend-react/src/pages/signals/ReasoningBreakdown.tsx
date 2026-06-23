import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import type { GateNodeDto, ScoreBreakdownDto } from '../../api/signals.ts';

// Renders the FROZEN Phase-20 reasoning contract (C-2.6/C-2.26), ported from Angular's
// reasoning-breakdown-panel: composite-vs-threshold gauge, stacked contribution bar + per-indicator
// rows, and the recursive gate checklist with actual operand values. Visually demonstrates the
// renderer invariant composite = Σ contributions ÷ weightDenominator. Derived percentages are
// computed inline, never stored.

const SEGMENT_BG = ['bg-accent', 'bg-bull', 'bg-warn', 'bg-bear'];

function fmt(value: number | string): string {
  return formatDecimal(String(value), 4);
}

function reasonTone(reason: string): string {
  switch (reason) {
    case 'REQUIRED':
      return 'text-accent ring-accent/40';
    case 'ACTIVATED':
      return 'text-bull ring-bull/40';
    case 'MARGIN_NOT_MET':
      return 'text-warn ring-warn/40';
    default:
      return 'text-ay-muted ring-ay-border';
  }
}

function operandText(node: GateNodeDto): string {
  return Object.entries(node.operands ?? {})
    .map(([name, value]) => `${name}=${value ?? 'n/a'}`)
    .join(', ');
}

function GateNode({ node }: { node: GateNodeDto }) {
  return (
    <li>
      <span className={node.passed ? 'text-bull' : 'text-bear'}>{node.passed ? '✔' : '✘'}</span>{' '}
      {node.rule ? (
        <code className="rounded bg-surface-2 px-1 py-0.5 text-xs">{node.rule}</code>
      ) : (
        <strong>{node.kind}</strong>
      )}
      {node.operands && <span className="text-ay-muted"> {operandText(node)}</span>}
      {node.children && node.children.length > 0 && (
        <ul className="ml-4 flex list-none flex-col gap-1 pl-2">
          {node.children.map((child, i) => (
            <GateNode key={i} node={child} />
          ))}
        </ul>
      )}
    </li>
  );
}

export function ReasoningBreakdown({ breakdown }: { breakdown: ScoreBreakdownDto }) {
  const compositePct = Math.min(100, Number(breakdown.composite) * 100);
  const thresholdPct = Math.min(100, Number(breakdown.threshold) * 100);
  const activatedTotal = breakdown.indicators
    .filter((e) => e.activated)
    .reduce((sum, e) => sum + Number(e.contribution), 0);

  return (
    <div className="flex flex-col gap-4">
      <section>
        <h3 className="mb-1 text-sm font-semibold text-ay-text">Composite vs threshold</h3>
        <div className="relative h-3.5 overflow-hidden rounded-full bg-surface-2">
          <div
            className={cn('h-full', breakdown.passed ? 'bg-bull' : 'bg-warn')}
            style={{ width: `${compositePct}%` }}
          />
          <div className="absolute -top-0.5 bottom-[-2px] w-0.5 bg-bear" style={{ left: `${thresholdPct}%` }} />
        </div>
        <div className="mt-1 text-xs text-ay-muted">
          composite {fmt(breakdown.composite)} / threshold {fmt(breakdown.threshold)} — required-only{' '}
          {fmt(breakdown.requiredComposite)}, weight denominator {fmt(breakdown.weightDenominator)}{' '}
          (invariant: composite = Σ contributions ÷ denominator)
        </div>
      </section>

      <section>
        <h3 className="mb-1 text-sm font-semibold text-ay-text">Contributions (w·s)</h3>
        <div className="flex h-[18px] overflow-hidden rounded-md bg-surface-2" aria-label="Stacked indicator contributions">
          {breakdown.indicators.map((e, i) =>
            e.activated && activatedTotal > 0 ? (
              <div
                key={e.alias}
                className={cn('h-full', SEGMENT_BG[i % SEGMENT_BG.length])}
                style={{ width: `${(Number(e.contribution) / activatedTotal) * 100}%` }}
                title={`${e.alias}: ${fmt(e.contribution)}`}
              />
            ) : null,
          )}
        </div>
        <div className="mt-2 flex flex-col gap-1.5 text-sm">
          {breakdown.indicators.map((e) => (
            <div
              key={e.alias}
              className="grid grid-cols-[1fr_auto_auto] items-center gap-2 rounded-lg border border-ay-border px-2 py-1.5"
            >
              <span className="min-w-0">
                <strong>{e.alias}</strong>
                <span className="text-ay-muted">
                  {' '}
                  {e.name} · {e.timeframe} · raw {e.rawValue === null ? '—' : String(e.rawValue)}
                </span>
              </span>
              <span className="tabular-nums">
                s {e.score === null ? '—' : fmt(e.score)} × w {fmt(e.weight)} = {fmt(e.contribution)}
              </span>
              <span
                className={cn(
                  'whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
                  reasonTone(e.activationReason),
                )}
              >
                {e.activationReason}
                {e.optional ? ' · opt' : ''}
              </span>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h3 className="mb-1 text-sm font-semibold text-ay-text">Gate checklist</h3>
        <ul className="flex list-none flex-col gap-1 pl-2 text-sm">
          <GateNode node={breakdown.gate} />
        </ul>
      </section>
    </div>
  );
}
