import { cn } from '../../lib/cn.ts';
import { compareDecimal, formatDecimal, isNegative } from '../../lib/decimal.ts';

// Signed delta cell: green when positive, red when negative, muted dash when null/absent. The sign is
// rendered explicitly (text cue, never colour-only — a11y #2). Decimal-string in, never parseFloat.
interface ValueDeltaCellProps {
  value: string | null;
  digits?: number;
  suffix?: string;
}

export function ValueDeltaCell({ value, digits = 2, suffix = '' }: ValueDeltaCellProps) {
  if (value == null) {
    return <span className="text-ay-muted">—</span>;
  }
  const zero = compareDecimal(value, '0') === 0;
  const neg = isNegative(value);
  const tone = zero ? '' : neg ? 'text-bear' : 'text-bull';
  const sign = !neg && !zero ? '+' : '';
  return (
    <span className={cn('tabular-nums', tone)}>
      {sign}
      {formatDecimal(value, digits)}
      {suffix}
    </span>
  );
}
