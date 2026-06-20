import { cn } from '../../lib/cn.ts';
import { oiIntMeta, type OiInterpretation, type OiSeverity } from '../../core/oiInterpretation.ts';

// 4-state OI interpretation badge (a11y #2: the text label always renders — colour is never the sole
// signal; the price/OI arrow rides as a tooltip; null → em-dash + sr-only). Ported from oi-int-badge.ts.

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
      title={meta.arrow}
      className={cn(
        'inline-block rounded px-2 py-0.5 text-xs font-medium ring-1',
        TONE[meta.severity],
      )}
    >
      {meta.label}
    </span>
  );
}
