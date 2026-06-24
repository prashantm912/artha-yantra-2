import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '../../lib/cn.ts';
import { formatDecimal } from '../../lib/decimal.ts';
import { useSignals, useSignalsLive, type SignalDto } from '../../api/signals.ts';
import { SignalTakeTicket } from '../scalper/SignalTakeTicket.tsx';

// A compact live-signals list (extracted so the Signals page AND the Scalping Cockpit share one feed
// without duplicating the REST snapshot + STOMP live-merge plumbing). Status-scoped; rows are
// click-through deep-links to the full Signals page. With `takeable` (the cockpit's paper-trading
// console) each row also carries a per-signal "Take (paper)" action that opens a prefilled defined-risk
// paper ticket. The full /signals page keeps its richer take/dismiss/reasoning panel.

function sideTone(side: SignalDto['side']): string {
  return side === 'BUY' ? 'text-bull' : 'text-bear';
}

interface SignalsFeedPanelProps {
  /** null = all statuses; the cockpit pins ACTIVE. */
  status?: string | null;
  /** Cap the rendered rows (the query already ring-bounds the cache). */
  limit?: number;
  /** Render a per-signal "Take (paper)" action (the cockpit paper-trading console). Read-only off. */
  takeable?: boolean;
}

export function SignalsFeedPanel({
  status = 'ACTIVE',
  limit = 30,
  takeable = false,
}: SignalsFeedPanelProps) {
  const q = useSignals(status);
  useSignalsLive(status);
  const rows = useMemo(() => (q.data?.items ?? []).slice(0, limit), [q.data, limit]);
  // Which signal's inline paper ticket is open (cockpit `takeable` mode); one at a time.
  const [openId, setOpenId] = useState<number | null>(null);

  return (
    <div className="max-h-[26rem] overflow-auto rounded-lg border border-ay-border">
      {rows.length > 0 ? (
        <ul className="divide-y divide-ay-border">
          {rows.map((s) => (
            <li key={s.id} className="flex flex-col gap-2 px-2 py-2">
              <div className="flex items-center gap-2">
                <Link
                  to={`/signals`}
                  className="grid min-w-0 flex-1 grid-cols-[3.2rem_1fr_auto] items-center gap-2 text-sm hover:underline"
                  aria-label={`Open signal ${s.exchange}:${s.tradingsymbol} ${s.signalType} ${s.side}`}
                >
                  <span className="tabular-nums text-ay-muted">{s.generatedAt.slice(11, 16)}</span>
                  <span className="truncate">
                    {s.exchange}:{s.tradingsymbol}
                  </span>
                  <span className="flex items-center gap-2">
                    {s.entryPrice && (
                      <span className="tabular-nums text-ay-muted">
                        {formatDecimal(s.entryPrice, 2)}
                      </span>
                    )}
                    <span className={cn('text-xs font-semibold', sideTone(s.side))}>
                      {s.signalType} {s.side}
                    </span>
                  </span>
                </Link>
                {takeable && openId !== s.id && (
                  <button
                    type="button"
                    onClick={() => setOpenId(s.id)}
                    className="whitespace-nowrap rounded-md bg-bull/15 px-2 py-1 text-xs font-semibold text-bull ring-1 ring-bull/40 hover:bg-bull/25"
                  >
                    Take (paper)
                  </button>
                )}
              </div>
              {takeable && openId === s.id && (
                <SignalTakeTicket signal={s} onDone={() => setOpenId(null)} />
              )}
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
