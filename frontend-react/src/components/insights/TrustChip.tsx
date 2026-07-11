import { cn } from '../../lib/cn.ts';
import type { InsightTrust } from '../../api/insights.ts';

// The standardized data-trust chip (INT design §8.4) — the I1 slice: rendered inside feed rows, Focus
// items and the explain-drawer banner. State is carried as TEXT ("Trusted"/"Degraded"/"Blocked"), never
// colour alone (a11y: colour is not the only signal), with the reasons + asOf on the hover title so the
// "why" is one hover away. Tone is a coloured RING, not a hue fill — a hue tint lifts the background
// luminance under same-hue text and breaks WCAG AA (the SentimentBadge/OiBadge4 house convention). The
// ContextStrip/digest-page rollout of this chip is I2.

const TONE: Record<InsightTrust, string> = {
  OK: 'text-bull ring-bull/40',
  DEGRADED: 'text-warn ring-warn/40',
  BLOCKED: 'text-bear ring-bear/40',
};

const LABEL: Record<InsightTrust, string> = {
  OK: 'Trusted',
  DEGRADED: 'Degraded',
  BLOCKED: 'Blocked',
};

interface TrustChipProps {
  state: InsightTrust;
  reasons?: string[] | null;
  asOf?: string | null;
  className?: string;
}

export function TrustChip({ state, reasons, asOf, className }: TrustChipProps) {
  const parts: string[] = [];
  if (reasons && reasons.length > 0) parts.push(reasons.join('; '));
  if (asOf) parts.push(`as of ${asOf}`);
  const title = parts.length > 0 ? parts.join(' · ') : undefined;
  return (
    <span
      data-trust={state}
      title={title}
      className={cn(
        'inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-medium ring-1',
        TONE[state],
        className,
      )}
    >
      {LABEL[state]}
    </span>
  );
}
