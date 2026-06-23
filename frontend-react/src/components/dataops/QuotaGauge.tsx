import { type WindowStat } from '../../api/dataops.ts';
import { cn } from '../../lib/cn.ts';

/** Bar fill colour from the used/max threshold rule: bull <80%, warn 80–95%, bear ≥95%. */
function fillColor(pct: number): string {
  if (pct >= 95) return 'bg-bear';
  if (pct >= 80) return 'bg-warn';
  return 'bg-bull';
}

/** Upstox rate-quota gauge: a labelled bar per window (used/max), muted when not configured. */
export function QuotaGauge(props: { configured: boolean; windows: WindowStat[] }): React.JSX.Element {
  return (
    <div className="rounded border border-ay-border bg-surface-1 p-3" aria-label="Upstox quota">
      <h2 className="text-xs text-ay-muted">Upstox quota</h2>
      {!props.configured ? (
        <p className="mt-1 text-xs text-ay-muted">not configured (mock / analytics off)</p>
      ) : (
        <div className="mt-2 space-y-2">
          {props.windows.map((w) => {
            const pct = w.max > 0 ? Math.min(100, (w.used / w.max) * 100) : 0;
            return (
              <div key={w.window}>
                <div className="flex items-baseline justify-between text-xs text-ay-text">
                  <span>{w.window}</span>
                  <span className="text-ay-muted">
                    {w.used} / {w.max}
                  </span>
                </div>
                <div
                  className="mt-1 h-2 rounded-full bg-surface-2"
                  role="progressbar"
                  aria-label={`${w.window} quota used`}
                  aria-valuenow={w.used}
                  aria-valuemin={0}
                  aria-valuemax={w.max}
                >
                  <div
                    className={cn('h-2 rounded-full', fillColor(pct))}
                    style={{ width: `${pct}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
