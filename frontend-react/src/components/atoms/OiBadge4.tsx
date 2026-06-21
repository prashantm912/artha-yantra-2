import { cn } from '../../lib/cn.ts';
import { oiIntMeta, type OiInterpretation, type OiSeverity } from '../../core/oiInterpretation.ts';

// 4-state OI interpretation badge (oipulse dense-table style): the abbreviation (L.B./S.B./S.C./L.U.)
// + a price-direction glyph is the VISIBLE non-colour cue, the FULL label rides as the accessible name
// (aria-label) + tooltip (a11y #2 — distinguishable without colour; null → em-dash + sr-only). The ring
// (not a solid fill) keeps WCAG contrast safe across themes — the only divergence from oipulse's fill.

const TONE: Record<OiSeverity, string> = {
  success: 'text-bull ring-bull/40',
  danger: 'text-bear ring-bear/40',
  info: 'text-accent ring-accent/40',
  warn: 'text-warn ring-warn/40',
};

export function OiBadge4({ value }: { value: OiInterpretation | null }) {
  if (!value) {
    return (
      <>
        <span aria-hidden="true" className="text-ay-muted">
          —
        </span>
        <span className="ay-sr-only">no interpretation</span>
      </>
    );
  }
  const meta = oiIntMeta(value);
  return (
    <span
      aria-label={meta.label}
      title={`${meta.label} — ${meta.arrow}`}
      className={cn(
        'inline-block whitespace-nowrap rounded px-2 py-0.5 text-xs font-semibold ring-1',
        TONE[meta.severity],
      )}
    >
      <span aria-hidden="true">{meta.glyph}</span> {meta.abbr}
    </span>
  );
}
