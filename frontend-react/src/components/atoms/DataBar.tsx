// In-cell magnitude bar (ported from data-bar.ts): width = |value|/max (magnitude only); colour
// conveys direction; the numeric label rides on top so the cell is never colour-only (a11y #2).

export type BarTone = 'bull' | 'bear' | 'neutral';

const FILL: Record<BarTone, string> = {
  bull: 'color-mix(in srgb, var(--ay-bull) 22%, transparent)',
  bear: 'color-mix(in srgb, var(--ay-bear) 22%, transparent)',
  neutral: 'color-mix(in srgb, var(--ay-text-muted) 22%, transparent)',
};

interface DataBarProps {
  value: number;
  max: number;
  label: string;
  tone?: BarTone;
}

export function DataBar({ value, max, label, tone = 'neutral' }: DataBarProps) {
  const pct = max > 0 ? Math.min(100, (Math.abs(value) / max) * 100) : 0;
  return (
    <span className="relative block w-full overflow-hidden rounded text-right tabular-nums">
      <span
        aria-hidden="true"
        className="absolute inset-y-0 left-0 rounded"
        style={{ width: `${pct}%`, background: FILL[tone] }}
      />
      <span className="relative px-1 text-xs text-ay-text">{label}</span>
    </span>
  );
}
