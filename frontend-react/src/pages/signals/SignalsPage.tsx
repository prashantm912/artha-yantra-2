import { useCallback, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Inbox } from 'lucide-react';
import { formatDecimal } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import {
  SIGNAL_STATUSES,
  useDismissSignal,
  useSignalDetail,
  useSignals,
  useSignalsLive,
  useTakeSignal,
  type SignalDto,
} from '../../api/signals.ts';
import { ManualVerifyChecklist } from '../../components/ManualVerifyChecklist.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { ReasoningBreakdown } from './ReasoningBreakdown.tsx';

// /signals cockpit page (master plan §20 parity, C-2.24/C-2.26): live feed + history in one
// scrollable table, click-through to the per-indicator reasoning breakdown. A published strategy's
// call lands here over STOMP; Take/Dismiss round-trip and refresh the row. Charts/journal deep-links
// arrive with their pages (PR-C9/C10).
// Revamp rollout (Trading screens): the sr-only h1 becomes the visible signature PageHeader (text
// preserved). The live feed table is wrapped in QueryState (error/empty/loading split) — the empty
// copy is preserved byte-identical. The detail aside + all WS/Take/Dismiss behaviour is untouched.

function statusTone(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'text-bull ring-bull/40';
    case 'TAKEN':
      return 'text-accent ring-accent/40';
    case 'DISMISSED':
      return 'text-bear ring-bear/40';
    default:
      return 'text-ay-muted ring-ay-border';
  }
}

function Badge({ label, tone }: { label: string; tone: string }) {
  return (
    <span className={cn('whitespace-nowrap rounded px-1.5 py-0.5 text-xs font-semibold ring-1', tone)}>
      {label}
    </span>
  );
}

export function SignalsPage() {
  const [status, setStatus] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const q = useSignals(status);
  useSignalsLive(status);
  const take = useTakeSignal();
  const dismiss = useDismissSignal();

  const rows = useMemo(() => q.data?.items ?? [], [q.data]);
  const selected = useMemo(() => rows.find((r) => r.id === selectedId) ?? null, [rows, selectedId]);

  // The live frame may omit scalperDetail — hydrate the selected signal via GET /signals/{id}.
  const detail = useSignalDetail(selectedId);
  const scalperDetail = detail.data?.scalperDetail ?? null;
  const [confirmed, setConfirmed] = useState(false);
  const onConfirmedChange = useCallback((v: boolean) => setConfirmed(v), []);
  // A non-scalper signal has no checklist → never gated.
  const takeBlocked = scalperDetail != null && !confirmed;

  const takenQty = (s: SignalDto): number | undefined =>
    s.suggestedQty ? Number(s.suggestedQty) : undefined;

  return (
    <div className="flex h-full flex-col">
      <PageHeader title="Live signals" subtitle="Live feed + history — click a row for the per-indicator reasoning breakdown" />
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-4 lg:grid-cols-[1.4fr_minmax(20rem,1fr)]">
      <section className="min-w-0">
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <Select
            value={status}
            options={[...SIGNAL_STATUSES]}
            onChange={(v) => setStatus(v || null)}
            ariaLabel="Status filter"
            placeholder="All statuses"
          />
          {status && (
            <button
              type="button"
              onClick={() => setStatus(null)}
              className="h-9 rounded-md border border-ay-border px-3 text-sm text-ay-muted hover:border-accent"
            >
              Clear
            </button>
          )}
          <button
            type="button"
            onClick={() => q.refetch()}
            disabled={q.isFetching}
            className="h-9 rounded-md border border-ay-border px-3 text-sm text-ay-text hover:border-accent disabled:opacity-50"
          >
            {q.isFetching ? '…' : '↻ Reload'}
          </button>
        </div>

        <QueryState
          query={q}
          isEmpty={() => rows.length === 0}
          empty={{ icon: Inbox, title: 'No signals yet — publish a strategy to start emitting signals.' }}
          errorTitle="Couldn't load signals"
          skeleton={<Skeleton variant="table-rows" rows={8} cols={7} className="rounded-lg border border-ay-border p-2" />}
        >
          {() => (
            <div className="max-h-[calc(100vh-12rem)] overflow-auto rounded-lg border border-ay-border">
              <table className="w-full border-collapse text-sm">
                <thead className="sticky top-0 bg-surface-1 text-left text-xs uppercase text-ay-muted">
                  <tr>
                    <th className="px-2 py-2 font-medium">Time</th>
                    <th className="px-2 py-2 font-medium">Strategy</th>
                    <th className="px-2 py-2 font-medium">Instrument</th>
                    <th className="px-2 py-2 font-medium">Type</th>
                    <th className="px-2 py-2 text-right font-medium">Entry</th>
                    <th className="px-2 py-2 text-right font-medium">Score</th>
                    <th className="px-2 py-2 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((s) => (
                    <tr
                      key={s.id}
                      role="button"
                      tabIndex={0}
                      onClick={() => setSelectedId(s.id)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          setSelectedId(s.id);
                        }
                      }}
                      aria-pressed={s.id === selectedId}
                      className={cn(
                        'cursor-pointer border-t border-ay-border hover:bg-surface-2 focus:bg-surface-2 focus:outline-none',
                        s.id === selectedId && 'bg-surface-2',
                      )}
                    >
                      <td className="px-2 py-2 nums">{s.generatedAt.slice(11, 19)}</td>
                      <td className="px-2 py-2">{s.strategyId ?? s.strategyVersionId?.slice(0, 8) ?? '—'}</td>
                      <td className="px-2 py-2">
                        {s.exchange}:{s.tradingsymbol}
                      </td>
                      <td className="px-2 py-2">
                        <Badge
                          label={`${s.signalType} ${s.side}`}
                          tone={s.signalType === 'ENTRY' ? 'text-bull ring-bull/40' : 'text-warn ring-warn/40'}
                        />
                      </td>
                      <td className="px-2 py-2 text-right nums">
                        {s.entryPrice ? formatDecimal(s.entryPrice, 2) : '—'}
                      </td>
                      <td className="px-2 py-2 text-right nums">{formatDecimal(s.compositeScore, 4)}</td>
                      <td className="px-2 py-2">
                        <Badge label={s.status} tone={statusTone(s.status)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </QueryState>
      </section>

      <aside className="overflow-auto rounded-lg border border-ay-border bg-surface-1 p-4">
        {selected ? (
          <>
            <div className="mb-3 text-xs text-ay-muted [overflow-wrap:anywhere]">
              #{selected.id} · v{selected.version ?? '?'} · checksum{' '}
              {selected.checksum?.slice(0, 12) ?? 'n/a'}… · SL{' '}
              {selected.stopLoss ? formatDecimal(selected.stopLoss, 2) : '—'} · target{' '}
              {selected.target ? formatDecimal(selected.target, 2) : '—'} · suggested qty{' '}
              {selected.suggestedQty ?? '—'}
            </div>
            {scalperDetail && (
              <div className="mb-4">
                <ManualVerifyChecklist detail={scalperDetail} onConfirmedChange={onConfirmedChange} />
              </div>
            )}
            <div className="mb-4 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => take.mutate({ id: selected.id, qty: takenQty(selected) })}
                disabled={take.isPending || takeBlocked}
                className="rounded-md bg-bull/15 px-3 py-1.5 text-sm font-medium text-bull ring-1 ring-bull/40 hover:bg-bull/25 disabled:opacity-50"
              >
                ✓ Taken
              </button>
              <button
                type="button"
                onClick={() => dismiss.mutate(selected.id)}
                disabled={dismiss.isPending}
                className="rounded-md bg-bear/15 px-3 py-1.5 text-sm font-medium text-bear ring-1 ring-bear/40 hover:bg-bear/25 disabled:opacity-50"
              >
                ✕ Dismiss
              </button>
              <Link
                to={`/charts?symbol=${encodeURIComponent(`${selected.exchange}:${selected.tradingsymbol}`)}&interval=${selected.interval}&signalId=${selected.id}`}
                className="rounded-md border border-ay-border px-3 py-1.5 text-sm hover:border-accent"
              >
                📈 View on chart
              </Link>
            </div>
            <ReasoningBreakdown breakdown={selected.scoreBreakdown} />
          </>
        ) : (
          <div className="grid h-full place-items-center text-ay-muted">
            Select a signal to see its reasoning.
          </div>
        )}
      </aside>
      </div>
    </div>
  );
}
