import { cn } from '../../lib/cn.ts';

// Moneyness pill (oipulse Big OI / chain): ITM green · ATM yellow · OTM muted. The text (ITM/ATM/OTM)
// is the cue; the ring tone is supportive only (a11y #2 — readable without colour). Ring not fill →
// WCAG-safe across themes (same convention as OiBadge4).

export type Moneyness = 'ITM' | 'ATM' | 'OTM';

const TONE: Record<Moneyness, string> = {
  ITM: 'text-bull ring-bull/40',
  ATM: 'text-warn ring-warn/40',
  OTM: 'text-ay-muted ring-ay-border',
};

export function MoneynessBadge({ value }: { value: Moneyness }) {
  return (
    <span
      className={cn(
        'inline-block whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-semibold ring-1',
        TONE[value],
      )}
    >
      {value}
    </span>
  );
}
