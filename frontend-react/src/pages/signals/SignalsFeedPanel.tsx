import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { useSignals, useSignalsLive, type SignalDto } from '../../api/signals.ts';

// A compact, read-only live-signals list (extracted so the Signals page AND the Scalping Cockpit share
// one feed without duplicating the REST snapshot + STOMP live-merge plumbing). Status-scoped; rows are
// click-through deep-links to the full Signals page. The full /signals page keeps its richer
// take/dismiss/reasoning panel — this is the at-a-glance cockpit variant.

function sideTone(side: SignalDto['side']): string {
  return side === 'BUY' ? 'text-bull' : 'text-bear';
}

interface SignalsFeedPanelProps {
  /** null = all statuses; the cockpit pins ACTIVE. */
  status?: string | null;
  /** Cap the rendered rows (the query already ring-bounds the cache). */
  limit?: number;
}

export function SignalsFeedPanel({ status = 'ACTIVE', limit = 30 }: SignalsFeedPanelProps) {
  const q = useSignals(status);
  useSignalsLive(status);
  const rows = useMemo(() => (q.data?.items ?? []).slice(0, limit), [q.data, limit]);

  return (
    <div className="max-h-[26rem] overflow-auto rounded-lg border border-ay-border">
      {rows.length > 0 ? (
        <ul className="divide-y divide-ay-border">
          {rows.map((s) => (
            <li key={s.id}>
              <Link
                to={`/signals`}
                className="grid grid-cols-[3.2rem_1fr_auto] items-center gap-2 px-2 py-2 text-sm hover:bg-surface-2"
              >
                <span className="tabular-nums text-ay-muted">{s.generatedAt.slice(11, 16)}</span>
                <span className="truncate">
                  {s.exchange}:{s.tradingsymbol}
                </span>
                <span className="flex items-center gap-2">
                  {s.entryPrice && (
                    <span className="tabular-nums text-ay-muted">{formatDecimal(s.entryPrice, 2)}</span>
                  )}
                  <span className={cn('text-xs font-semibold', sideTone(s.side))}>
                    {s.signalType} {s.side}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      ) : (
        <p className="p-3 text-sm text-ay-muted">
          {q.isLoading ? 'Loading…' : 'No live signals — publish a strategy to start emitting.'}
        </p>
      )}
    </div>
  );
}
