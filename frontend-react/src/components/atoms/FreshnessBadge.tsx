import { AlertTriangle, Circle, Clock } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import type { DataFreshness } from '../../api/types.ts';

// The ONE shared data-freshness chip (app-platform audit §11.17 / §4.2-1, Phase-3 slice A). Replaces
// the per-page ad-hoc "as of" strings and the one-off LiveDot/EodBadge so every analytics page header
// reads its provenance + staleness the same way. State is carried as a leading WORD
// ("Live"/"Stale"/"EOD"/"Derived"/"Static"), never colour alone (a11y house rule), and tone is a
// coloured RING — never a hue FILL, which lifts background luminance under same-hue text and breaks
// WCAG AA (the TrustChip/SentimentBadge convention). The source + exact age ride the hover title.

interface FreshnessBadgeProps {
  /** The envelope from the analytics response. Renders nothing when absent or provenance-less. */
  freshness?: DataFreshness | null;
  /**
   * Live-provenance age (seconds) beyond which the chip flips to the warn "Stale" state. Default 180
   * covers the ~2-min OI capture cadence plus a bucket; the server `complete=false` flag also flips it.
   */
  staleAfterSeconds?: number;
  /** Injectable wall-clock (ms) for deterministic tests; defaults to {@code Date.now()}. */
  now?: number;
  className?: string;
}

/** {@code 45s} / {@code 3m} / {@code 2h} / {@code 4d} — compact relative age. */
function formatAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86_400)}d`;
}

export function FreshnessBadge({
  freshness,
  staleAfterSeconds = 180,
  now,
  className,
}: FreshnessBadgeProps) {
  if (!freshness || !freshness.provenance) return null;
  const { provenance, asOf, staleSeconds, complete, source } = freshness;

  // Client-side age (so an idle LIVE page's chip visibly ages past the server-computed staleSeconds on
  // the next render), falling back to the server value when asOf is absent. The `+05:30` offset in the
  // ISO string makes Date.parse resolve the correct instant.
  const ageSec =
    asOf != null
      ? Math.max(0, Math.round(((now ?? Date.now()) - Date.parse(asOf)) / 1000))
      : (staleSeconds ?? null);

  const timeLabel = asOf != null ? asOf.slice(11, 16) : null; // HH:MM IST
  const dateLabel = asOf != null ? asOf.slice(0, 10) : null; // YYYY-MM-DD

  // Build the hover detail: source + captured-age + completeness.
  const titleParts: string[] = [];
  if (source) titleParts.push(`source: ${source}`);
  if (ageSec != null) titleParts.push(`captured ${formatAge(ageSec)} ago`);
  if (complete === false) titleParts.push('latest datapoint incomplete');
  const title = titleParts.length > 0 ? titleParts.join(' · ') : undefined;

  let tone: string;
  let label: string;
  let detail: string | null;
  let icon: 'live' | 'warn' | 'clock';

  if (provenance === 'live') {
    const stale = complete === false || (ageSec != null && ageSec > staleAfterSeconds);
    tone = stale ? 'text-warn ring-warn/40' : 'text-bull ring-bull/40';
    label = stale ? 'Stale' : 'Live';
    icon = stale ? 'warn' : 'live';
    // A live read stale by DAYS must not read the same as one stale by minutes: HH:MM alone makes
    // "yesterday's close" and "a chain from three weeks ago" identical chips. Past a day the date
    // joins the time so the age is legible at a glance, not only on the hover title. Under a day
    // the chip is unchanged, so every fresh/briefly-stale surface renders exactly as before.
    detail =
      stale && ageSec != null && ageSec >= 86_400 && dateLabel != null
        ? `${dateLabel} ${timeLabel}`
        : timeLabel;
  } else if (provenance === 'eod') {
    tone = 'text-ay-muted ring-ay-border';
    label = 'EOD';
    icon = 'clock';
    detail = dateLabel;
  } else if (provenance === 'derived') {
    tone = 'text-ay-muted ring-ay-border';
    label = 'Derived';
    icon = 'clock';
    detail = dateLabel;
  } else if (provenance === 'static') {
    tone = 'text-ay-muted ring-ay-border';
    label = 'Static';
    icon = 'clock';
    detail = null;
  } else {
    tone = 'text-ay-muted ring-ay-border';
    label = provenance;
    icon = 'clock';
    detail = dateLabel;
  }

  return (
    <span
      data-provenance={provenance}
      title={title}
      className={cn(
        'inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium ring-1',
        tone,
        className,
      )}
    >
      {icon === 'live' && (
        <Circle aria-hidden="true" className="ay-breathe size-2.5 fill-current" />
      )}
      {icon === 'warn' && <AlertTriangle aria-hidden="true" className="size-3" />}
      {icon === 'clock' && <Clock aria-hidden="true" className="size-3" />}
      <span>{label}</span>
      {/* "as of" marks this as the DATA timestamp, not a second wall-clock (audit 2026-07-02 §6). */}
      {detail && <span className="nums text-ay-muted">as of {detail}</span>}
    </span>
  );
}
