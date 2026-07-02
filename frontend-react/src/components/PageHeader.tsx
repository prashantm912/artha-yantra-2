import { useContext, type ReactNode } from 'react';
import { AlertTriangle, Circle } from 'lucide-react';
import { cn } from '../lib/cn.ts';
import { CompactPaneContext } from './paneContext.ts';
import { InfoTip } from './atoms/InfoTip.tsx';

// The ONE signature header lockup (revamp §1.7.2 / §4.0), reused by every hero page: a display-face
// <h1> (Newsreader via the text-h1 token) behind a 2px --ay-accent left-rule, an optional muted
// subtitle, and an optional right slot. NO breadcrumb on desktop (Q5 resolved: dropped). Keep exactly
// one <h1> per page — this replaces the old ay-sr-only h1 (still satisfies axe page-has-heading-one).

interface PageHeaderProps {
  /** The page title — becomes the single visible <h1>. */
  title: string;
  /** Optional one-line muted subtitle under the title. */
  subtitle?: ReactNode;
  /** Optional plain-language explanation of what the page shows + how to read it (#16) — rendered as
   *  a focusable ⓘ tooltip beside the title. */
  help?: string;
  /** Optional right-aligned slot (as-of stamp, freshness chip, range label). */
  right?: ReactNode;
}

export function PageHeader({ title, subtitle, help, right }: PageHeaderProps) {
  // Inside a Multiple-Window pane the hero lockup collapses to a hidden heading (audit §9.6);
  // the `right` slot (as-of / freshness) survives as a slim strip.
  const compact = useContext(CompactPaneContext);
  if (compact) {
    return (
      <header className={cn(right && 'mb-2 flex items-center justify-end gap-2')}>
        <h2 className="sr-only">{title}</h2>
        {right}
      </header>
    );
  }
  return (
    <header className="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
      <div className="border-l-2 border-accent pl-3">
        <h1 className="flex items-center gap-1.5 text-h1 text-ay-text">
          {title}
          {help && <InfoTip text={help} label={title} iconClassName="size-4" />}
        </h1>
        {subtitle && <p className="mt-0.5 text-body-sm text-ay-muted">{subtitle}</p>}
      </div>
      {right && <div className="flex shrink-0 items-center gap-2 self-start">{right}</div>}
    </header>
  );
}

// The live-state dot (§4.0): a slow breathing dot reads liveness without text. Stale stops the
// breathing and flips the dot + label to --ay-warn. The leading word ("Live"/"Stale") carries the
// meaning, so the glyph is aria-hidden.
interface LiveDotProps {
  /** When true, liveness is stale → no breathing, warn tone. */
  stale?: boolean;
  /** Optional trailing text (e.g. "13:30:00 IST"). */
  detail?: string;
  className?: string;
}

export function LiveDot({ stale = false, detail, className }: LiveDotProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-caption',
        stale ? 'text-warn' : 'text-bull',
        className,
      )}
    >
      {stale ? (
        <AlertTriangle aria-hidden="true" className="size-3.5" />
      ) : (
        <Circle aria-hidden="true" className="ay-breathe size-2.5 fill-current" />
      )}
      <span className="font-medium">{stale ? 'Stale' : 'Live'}</span>
      {/* "as of" marks this as the DATA timestamp — without it the detail read as a second
          wall-clock ticking seconds apart from the topbar IST clock (audit 2026-07-02 §6). */}
      {detail && <span className="nums text-ay-muted">as of {detail}</span>}
    </span>
  );
}
