import { cn } from '../../lib/cn.ts';
import type { InsightBand, InsightEvidence, InsightSeverity } from '../../api/insights.ts';

// Small shared presentational atoms for the insight surfaces (feed / Focus / explain drawer). Each
// carries its meaning as TEXT (band letter, severity word), never colour alone — colour only reinforces.
// Tone is a coloured RING, not a hue fill: a same-hue tint lifts the background luminance under the
// coloured text and drops it below WCAG AA (the SentimentBadge/OiBadge4 house convention).

const BAND_TONE: Record<InsightBand, string> = {
  A: 'text-bull ring-bull/40',
  B: 'text-accent ring-accent/40',
  C: 'text-warn ring-warn/40',
  D: 'text-ay-muted ring-ay-border',
};

/** The A/B/C/D priority band chip. `null` band = an unscored (BLOCKED) insight → a muted dash. */
export function BandChip({ band, className }: { band: InsightBand | null; className?: string }) {
  if (band == null) {
    return (
      <span
        aria-label="Unscored — data trust blocked, no priority computed"
        title="Unscored — data trust is BLOCKED, so no priority was computed"
        className={cn(
          'inline-flex size-5 items-center justify-center rounded text-[11px] font-semibold text-ay-muted ring-1 ring-ay-border',
          className,
        )}
      >
        —
      </span>
    );
  }
  return (
    <span
      aria-label={`Priority band ${band}`}
      className={cn(
        'inline-flex size-5 items-center justify-center rounded text-[11px] font-semibold ring-1',
        BAND_TONE[band],
        className,
      )}
    >
      {band}
    </span>
  );
}

const SEVERITY_TONE: Record<InsightSeverity, string> = {
  INFO: 'text-ay-muted ring-ay-border',
  NOTICE: 'text-accent ring-accent/40',
  WARN: 'text-warn ring-warn/40',
  CRITICAL: 'text-bear ring-bear/40',
};

/** The severity pill (INFO/NOTICE/WARN/CRITICAL). */
export function SeverityBadge({ severity, className }: { severity: InsightSeverity; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-medium ring-1',
        SEVERITY_TONE[severity],
        className,
      )}
    >
      {severity}
    </span>
  );
}

/**
 * One evidence pointer as a chip: `label: value`, with the source endpoint + asOf on the hover title so
 * "trust but verify" is one hover (§8.7). I1 renders the source as a caption rather than a live
 * deep-link — the navigable endpoint→page resolver rides the I2 ContextStrip wave.
 */
export function EvidenceChip({ ev, className }: { ev: InsightEvidence; className?: string }) {
  const src = ev.source;
  const titleParts: string[] = [];
  if (src?.endpoint) titleParts.push(src.endpoint);
  if (src?.asOf) titleParts.push(`as of ${src.asOf}`);
  const title = titleParts.length > 0 ? titleParts.join(' · ') : undefined;
  return (
    <span
      title={title}
      className={cn(
        'inline-flex max-w-full items-center gap-1 rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-ay-text',
        className,
      )}
    >
      <span className="text-ay-muted">{ev.label}:</span>
      <span className="truncate">{ev.value}</span>
    </span>
  );
}
