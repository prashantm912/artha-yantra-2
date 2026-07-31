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

// `asOf` must be RENDERED in IST regardless of the offset the server serialised it with. Slicing the
// ISO string (the old approach) only works when the wire carries `+05:30`; market-data runs on
// Clock.systemUTC(), so a 15:30 IST capture arrives as ...T10:00:00Z and character-slicing rendered
// it as "10:00" — a wrong wall-clock on the one field the badge exists to communicate.
const IST_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Kolkata',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const IST_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Kolkata',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

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
  const asOfMs = asOf != null ? Date.parse(asOf) : Number.NaN;
  const asOfValid = !Number.isNaN(asOfMs);
  const nowMs = now ?? Date.now();
  const ageSec = asOfValid
    ? Math.max(0, Math.round((nowMs - asOfMs) / 1000))
    : (staleSeconds ?? null);

  const timeLabel = asOfValid ? IST_TIME.format(asOfMs) : null; // HH:MM IST
  const dateLabel = asOfValid ? IST_DATE.format(asOfMs) : null; // YYYY-MM-DD IST

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
    // A stale live read from a PREVIOUS IST session must not read like one from minutes ago: HH:MM
    // alone makes "yesterday's close" and "a chain from three weeks ago" identical chips. The test
    // is the IST CALENDAR DATE, not elapsed hours — an elapsed-hours threshold gets the commonest
    // case wrong, since a 15:30 capture read at 09:15 next morning is only ~17.5 h old yet is a
    // different session, and that overnight view is how this badge is usually seen. Same-day stale
    // chips are unchanged, so every intraday surface renders exactly as before.
    detail =
      stale && dateLabel != null && dateLabel !== IST_DATE.format(nowMs)
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
