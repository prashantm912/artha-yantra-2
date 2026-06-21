import { cn } from '../../lib/cn.ts';

// Signed integer cell for OI-count deltas (grouped en-IN, explicit sign). Green +, red −, muted dash.
// Sibling to ValueDeltaCell (which is for decimal-STRING prices); this is for `long` counts (numbers).
// The sign is an explicit text cue — never colour-only (a11y #2).

export function SignedCount({ value }: { value: number | null }) {
  if (value == null) return <span className="text-ay-muted">—</span>;
  const tone = value === 0 ? '' : value > 0 ? 'text-bull' : 'text-bear';
  return (
    <span className={cn('tabular-nums', tone)}>
      {value > 0 ? '+' : ''}
      {value.toLocaleString('en-IN')}
    </span>
  );
}
