import { formatDecimal } from '../../lib/decimal.ts';
import {
  usePaperEvents,
  usePaperEventsLive,
  type PaperEvent,
} from '../../api/paper.ts';

const EVENT_LABELS: Record<PaperEvent['kind'], string> = {
  OPENED: 'Opened',
  BRACKET_HIT: 'Bracket hit',
  CLOSED: 'Closed',
  SETTLED: 'Settled',
};

function fmtTime(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso));
}

export function OrderEventTimeline({ positionId }: { positionId: number }) {
  const filter = { positionId };
  const query = usePaperEvents(filter);
  usePaperEventsLive(filter);

  if (query.isPending) {
    return (
      <div
        role="status"
        className="rounded-md border border-ay-border bg-surface-1 p-2 text-xs text-ay-muted"
      >
        Loading order events…
      </div>
    );
  }

  if (query.isError) {
    return (
      <div
        role="alert"
        className="rounded-md border border-ay-border bg-surface-1 p-2 text-xs text-bear"
      >
        Couldn't load order events.
      </div>
    );
  }

  const events = [...(query.data?.items ?? [])].sort((a, b) => {
    const byTime = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
    return byTime || a.id - b.id;
  });

  if (events.length === 0) {
    return (
      <p className="rounded-md border border-ay-border bg-surface-1 p-2 text-xs text-ay-muted">
        No events recorded for this position yet.
      </p>
    );
  }

  return (
    <ol aria-label="Order events" className="flex flex-col">
      {events.map((event, index) => (
        <li key={event.id} className="flex gap-2">
          <div aria-hidden="true" className="flex w-3 shrink-0 flex-col items-center">
            <span className="mt-3 size-2 rounded-full bg-accent" />
            {index < events.length - 1 && <span className="w-px flex-1 bg-ay-border" />}
          </div>
          <div className="mb-2 flex-1 rounded-md border border-ay-border bg-surface-1 p-2 text-xs">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="font-medium text-ay-text">{EVENT_LABELS[event.kind]}</span>
              <span className="text-ay-muted">{fmtTime(event.createdAt)} IST</span>
            </div>
            {(event.reason != null || event.realizedPnl != null) && (
              <div className="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-ay-muted">
                {event.reason != null && <span>{event.reason}</span>}
                {event.realizedPnl != null && (
                  <span className="nums text-ay-text">
                    Realized P&amp;L {formatDecimal(event.realizedPnl, 2)}
                  </span>
                )}
              </div>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}
