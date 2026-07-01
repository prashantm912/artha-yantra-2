import { Fragment, useMemo, useState } from 'react';
import { Ban } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import {
  useSignalRejections,
  useRejectionRailCounts,
  type Num,
  type SignalRejectionDto,
} from '../../api/signalRejections.ts';

// /signal-rejections — WHY the live §12.3 confluence gate blocked each scalper chart-entry. Every row
// is a bar where the strategy's chart entry fired but the gate returned no Decision: the first failing
// rail + its margin, and (expanded) the full rail-by-rail checklist, the dot-by-dot Connect-the-Dots
// confluence, and the raw OI/macro/chart context. Live-only data (no rows on backtest). The owner uses
// this to see how far below threshold the composite sits and which condition blocks most.

/** Today's calendar date (YYYY-MM-DD) in IST, robust to the browser timezone. */
function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

/** The [00:00, next-00:00) IST bounds of a day, as ISO offset datetimes the API parses. */
function dayBoundsIst(date: string): { from: string; to: string } {
  const next = new Date(new Date(`${date}T00:00:00+05:30`).getTime() + 24 * 3600 * 1000);
  const nextDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(next);
  return { from: `${date}T00:00:00+05:30`, to: `${nextDay}T00:00:00+05:30` };
}

function fmtNum(v: Num, dp = 4): string {
  if (v == null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  if (Number.isNaN(n)) return String(v);
  return n.toLocaleString('en-IN', { maximumFractionDigits: dp });
}

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

/** A signed-margin pill: negative (short of threshold) reads bearish, ≥0 bullish. */
function Margin({ value }: { value: Num }) {
  if (value == null || value === '') return <span className="text-ay-muted">—</span>;
  const n = typeof value === 'number' ? value : parseFloat(value);
  const cls = Number.isNaN(n) ? 'text-ay-muted' : n < 0 ? 'text-bear' : 'text-bull';
  return <span className={cls}>{n >= 0 ? '+' : ''}{fmtNum(value)}</span>;
}

export function RejectionsPage() {
  const [date, setDate] = useState<string>(todayIst());
  const [rail, setRail] = useState<string>('');
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const isToday = date === todayIst();
  const bounds = useMemo(() => dayBoundsIst(date), [date]);

  const q = useSignalRejections(null, rail || null, bounds.from, bounds.to);
  const counts = useRejectionRailCounts(null, bounds.from, bounds.to);
  const rows = q.data?.items ?? [];
  const railOptions = counts.data?.items ?? [];

  const toggle = (id: number) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <LoadBeat>
      <PageHeader
        title="Signal Rejections"
        subtitle="Every scalper entry the live confluence gate blocked — the failing rail, its margin, and the full breakdown"
        help="When a scalper's chart entry fires but the §12.3 confluence gate returns no decision, the entry is blocked and recorded here. Each row shows the FIRST failing rail and how far its operand sat from the threshold; expand a row for the rail-by-rail checklist, the dot-by-dot confluence score, and the raw OI/macro/chart inputs. Live-only — backtests never run the gate."
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput
          value={date}
          ariaLabel="Rejection day"
          onChange={(v) => setDate(v ?? todayIst())}
        />
        {isToday && <span className="text-xs text-ay-muted">live (today)</span>}
        <Select
          value={rail}
          ariaLabel="Filter by blocking rail"
          onChange={setRail}
          options={[
            { value: '', label: 'All rails' },
            ...railOptions.map((c) => ({ value: c.rail, label: `${c.rail} (${c.count})` })),
          ]}
        />
      </div>

      {/* Which rail blocks most — a quick rollup strip */}
      {railOptions.length > 0 && (
        <div className="mb-3 flex flex-wrap gap-2">
          {railOptions.map((c) => (
            <button
              key={c.rail}
              type="button"
              onClick={() => setRail(rail === c.rail ? '' : c.rail)}
              className={cn(
                'rounded-md border px-2 py-1 text-xs',
                rail === c.rail ? 'bg-surface-2 text-ay-text' : 'text-ay-muted hover:text-ay-text',
              )}
            >
              {c.rail} <span className="font-semibold text-ay-text">{c.count}</span>
            </button>
          ))}
        </div>
      )}

      <QueryState
        query={q}
        isEmpty={() => rows.length === 0}
        empty={{
          icon: Ban,
          title: isToday
            ? 'No blocked entries today — either nothing fired the chart entry yet, or the gate is passing.'
            : 'No blocked entries recorded for this day.',
        }}
        errorTitle="Couldn't load signal rejections"
        skeleton={<Skeleton variant="table-rows" />}
      >
        {() => (
          <BeatBlock>
            <div className="max-h-[70vh] overflow-auto rounded-md border" style={{ contain: 'paint' }}>
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-surface-1 text-left text-xs text-ay-muted">
                  <tr>
                    <th className="px-2 py-2 font-medium">Time (IST)</th>
                    <th className="px-2 py-2 font-medium">Strategy</th>
                    <th className="px-2 py-2 font-medium">Symbol</th>
                    <th className="px-2 py-2 font-medium">Side</th>
                    <th className="px-2 py-2 font-medium">Blocking rail</th>
                    <th className="px-2 py-2 text-right font-medium">Operand</th>
                    <th className="px-2 py-2 text-right font-medium">Threshold</th>
                    <th className="px-2 py-2 text-right font-medium">Margin</th>
                    <th className="px-2 py-2 text-right font-medium">Composite</th>
                    <th className="px-2 py-2 font-medium">Reason</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <Fragment key={r.id}>
                      <tr
                        className="cursor-pointer border-t hover:bg-surface-2/50"
                        onClick={() => toggle(r.id)}
                      >
                        <td className="whitespace-nowrap px-2 py-1.5 text-ay-muted">{fmtTime(r.barTime)}</td>
                        <td className="px-2 py-1.5 text-ay-text">{r.strategySlug}</td>
                        <td className="whitespace-nowrap px-2 py-1.5 text-ay-muted">{r.tradingsymbol}</td>
                        <td className="px-2 py-1.5">
                          {r.side ? (
                            <span className={r.side === 'CE' ? 'text-bull' : 'text-bear'}>{r.side}</span>
                          ) : (
                            <span className="text-ay-muted">—</span>
                          )}
                        </td>
                        <td className="px-2 py-1.5">
                          <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-text">
                            {r.blockingRail}
                          </span>
                        </td>
                        <td className="px-2 py-1.5 text-right tabular-nums">{fmtNum(r.blockingOperand)}</td>
                        <td className="px-2 py-1.5 text-right tabular-nums">{fmtNum(r.blockingThreshold)}</td>
                        <td className="px-2 py-1.5 text-right tabular-nums">
                          <Margin value={r.blockingMargin} />
                        </td>
                        <td className="px-2 py-1.5 text-right tabular-nums text-ay-muted">
                          {r.compositeScore == null ? '—' : `${fmtNum(r.compositeScore)} / ${fmtNum(r.compositeThreshold)}`}
                        </td>
                        <td className="px-2 py-1.5 text-xs text-ay-muted">{r.blockingReason}</td>
                      </tr>
                      {expanded.has(r.id) && (
                        <tr className="border-t bg-surface-2/30">
                          <td colSpan={10} className="px-3 py-3">
                            <RejectionDetail row={r} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="mt-2 text-xs text-ay-muted">
              Click any row to expand the full breakdown. {rows.length} blocked {rows.length === 1 ? 'entry' : 'entries'}
              {rail ? ` for rail “${rail}”` : ''}.
            </p>
          </BeatBlock>
        )}
      </QueryState>
    </LoadBeat>
  );
}

/** The expanded per-rejection breakdown: every rail checked, the confluence dots, and the raw context. */
function RejectionDetail({ row }: { row: SignalRejectionDto }) {
  const d = row.diagnostic;
  const conf = d.confluence;
  const ctx = d.context;
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
      {/* Rail checklist */}
      <section>
        <h3 className="mb-1 text-xs font-semibold uppercase text-ay-muted">Rails evaluated</h3>
        <table className="w-full text-xs">
          <tbody>
            {d.checks.map((c, i) => (
              <tr key={`${c.rail}-${i}`} className="border-t border-ay-border/40">
                <td className="py-1 pr-2">
                  <span className={c.pass ? 'text-bull' : 'text-bear'}>{c.pass ? '✓' : '✗'}</span>{' '}
                  {c.rail}
                </td>
                <td className="py-1 text-right tabular-nums text-ay-muted">
                  {c.operand == null ? '' : fmtNum(c.operand)}
                  {c.threshold == null ? '' : ` / ${fmtNum(c.threshold)}`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* Confluence dots */}
      <section>
        <h3 className="mb-1 text-xs font-semibold uppercase text-ay-muted">
          Confluence{' '}
          {conf ? (
            <span className="text-ay-text">
              {fmtNum(conf.aggregate)} / {fmtNum(conf.threshold)}
            </span>
          ) : (
            <span className="normal-case text-ay-muted">(not reached — blocked earlier)</span>
          )}
        </h3>
        {conf ? (
          <>
            <div className="mb-1 flex flex-wrap gap-2 text-xs text-ay-muted">
              <span className={conf.vwapAligned ? 'text-bull' : 'text-bear'}>VWAP {conf.vwapAligned ? 'ok' : '✗'}</span>
              <span className={conf.biasAligned ? 'text-bull' : 'text-bear'}>60m bias {conf.biasAligned ? 'ok' : '✗'}</span>
              {conf.standAside && <span className="text-bear">stand-aside</span>}
            </div>
            <div className="flex flex-wrap gap-1">
              {conf.dots.map((dot, i) => (
                <span
                  key={`${dot.dot}-${i}`}
                  title={`${dot.reason ?? dot.dot} · weight ${dot.weight}`}
                  className={cn(
                    'rounded px-1.5 py-0.5 text-[11px]',
                    dot.supports ? 'bg-bull/15 text-bull' : 'bg-bear/15 text-bear',
                  )}
                >
                  {dot.dot}
                </span>
              ))}
            </div>
          </>
        ) : (
          <p className="text-xs text-ay-muted">
            A hard rail blocked before the Connect-the-Dots score was computed.
          </p>
        )}
      </section>

      {/* Raw context */}
      <section>
        <h3 className="mb-1 text-xs font-semibold uppercase text-ay-muted">Context</h3>
        {ctx ? (
          <div className="grid grid-cols-1 gap-2 text-xs">
            <KeyVals title="OI" obj={ctx.oi} />
            <KeyVals title="Macro" obj={ctx.macro} />
            <KeyVals title="Chart" obj={ctx.chart} />
          </div>
        ) : (
          <p className="text-xs text-ay-muted">Blocked before the chain fetch — no OI/macro context.</p>
        )}
      </section>
    </div>
  );
}

/** Renders a flat record of raw context values (null-safe, compact). */
function KeyVals({ title, obj }: { title: string; obj?: Record<string, unknown> }) {
  if (!obj) return null;
  const entries = Object.entries(obj).filter(([, v]) => v !== null && v !== undefined);
  if (entries.length === 0) return null;
  return (
    <div>
      <div className="mb-0.5 font-semibold text-ay-text">{title}</div>
      <div className="grid grid-cols-2 gap-x-3 gap-y-0.5">
        {entries.map(([k, v]) => (
          <div key={k} className="flex justify-between gap-2">
            <span className="text-ay-muted">{k}</span>
            <span className="tabular-nums text-ay-text">
              {typeof v === 'number' ? fmtNum(v) : String(v)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
