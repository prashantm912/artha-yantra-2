import { cn } from '../../lib/cn.ts';

// Sentiment pill — the directional read used by Trending OI (5-level) and Participant-wise OI
// (Bullish/Bearish). The label IS the cue; the ring tone is supportive only (a11y #2). Ring not fill →
// WCAG-safe across themes (same convention as OiBadge4 / MoneynessBadge).

export type SentimentTone = 'bull' | 'bear' | 'neutral';

const TONE: Record<SentimentTone, string> = {
  bull: 'text-bull ring-bull/40',
  bear: 'text-bear ring-bear/40',
  neutral: 'text-ay-muted ring-ay-border',
};

export function SentimentBadge({ label, tone }: { label: string; tone: SentimentTone }) {
  return (
    <span
      className={cn(
        'inline-block whitespace-nowrap rounded px-2 py-0.5 text-xs font-semibold ring-1',
        TONE[tone],
      )}
    >
      {label}
    </span>
  );
}
