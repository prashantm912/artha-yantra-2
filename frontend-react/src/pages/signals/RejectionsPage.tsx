import { useMemo, useState } from 'react';
import { Ban } from 'lucide-react';
import { cn } from '../../lib/cn.ts';
import { Select } from '../../components/atoms/Select.tsx';
import { DateInput } from '../../components/atoms/DateInput.tsx';
import { Pager } from '../../components/atoms/Pager.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { LoadBeat, BeatBlock } from '../../components/LoadBeat.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import {
  useSignalRejections,
  useRejectionRailCounts,
  useShadowSummary,
  useDotHealth,
  type Num,
  type DotState,
  type RejectionDataHealth,
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

/**
 * The per-row data-health badge (F5 U3). Three states, each carrying a WORD — colour is never the
 * only cue:
 *   • "Degraded" (amber) — at least one gate input was ABSENT when this bar was scored; the tooltip
 *     and the expanded panel name which.
 *   • "OK" (muted) — every input was present.
 *   • "—" — either the row predates V054 (never computed) or it blocked before the chain fetch, so
 *     there is nothing to judge. Deliberately NOT rendered as "OK": absence of a verdict is not a
 *     clean bill of health.
 * A row on its OI root's monthly expiry carries an "expiry" note: the OI block is inert BY DESIGN
 * there (S24), so it is not counted against the row.
 */
function HealthBadge({ row }: { row: SignalRejectionDto }) {
  const h = row.dataHealth;
  if (!h || !h.contextBearing) {
    return (
      <span
        className="text-ay-muted"
        title={
          h
            ? 'Blocked before the chain fetch — no gate inputs to judge.'
            : 'Recorded before per-row data-health existed.'
        }
      >
        —
      </span>
    );
  }
  if (!h.degraded) {
    return (
      <span className="text-ay-muted" title={healthTitle(h)}>
        OK{h.oiSuppressed ? ' · expiry' : ''}
      </span>
    );
  }
  return (
    // The WORD is the non-colour cue (WCAG 1.4.1) — no glyph, deliberately: the confluence-dot chips
    // below already own ▲/▼ for supports/opposes, and reusing the shape here would read as the same
    // axis.
    <span className="text-warn" title={healthTitle(h)}>
      Degraded<span className="text-ay-muted"> ({h.flags.length})</span>
    </span>
  );
}

/** The badge's tooltip: which inputs were absent, plus the by-design expiry note when it applies. */
function healthTitle(h: RejectionDataHealth): string {
  const suppressed = h.oiSuppressed
    ? ' OI reads are inert BY DESIGN on this root’s monthly index expiry (S24) and are not counted.'
    : '';
  return h.flags.length === 0
    ? `Every gate input was present.${suppressed}`
    : `Absent gate inputs: ${h.flags.join(', ')}.${suppressed}`;
}

/** IST day + time for the dot-health as-of stamp. */
function fmtAsOf(iso: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Kolkata',
    day: '2-digit',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(iso));
}

/**
 * One gate-input dot's verdict card: the WORD (Live / Dead) plus a colour — green live, red for a dead
 * REQUIRED input (the alarm), amber for a dead OPTIONAL input (contributes nothing but not alarming).
 * Colour is never the sole cue — the word and the detail text always carry the state.
 */
function DotChip({ dot }: { dot: DotState }) {
  const verdict = dot.alive ? 'Live' : 'Dead';
  const textTone = dot.alive ? 'text-bull' : dot.required ? 'text-bear' : 'text-warn';
  const dotTone = dot.alive ? 'bg-bull' : dot.required ? 'bg-bear' : 'bg-warn';
  return (
    <div className="rounded-md border border-ay-border bg-surface-0 px-2.5 py-1.5" title={dot.detail}>
      <div className="flex items-center gap-1.5">
        <span aria-hidden="true" className={cn('size-2 rounded-full', dotTone)} />
        <span className="font-medium text-ay-text">{dot.dot}</span>
        {dot.required && (
          <span className="rounded bg-surface-2 px-1 py-0.5 text-[10px] uppercase tracking-wide text-ay-muted">
            required
          </span>
        )}
        {dot.frozen && (
          <span className="rounded bg-surface-2 px-1 py-0.5 text-[10px] uppercase tracking-wide text-ay-muted">
            frozen
          </span>
        )}
        {dot.neverCrossing && (
          <span className="rounded bg-surface-2 px-1 py-0.5 text-[10px] uppercase tracking-wide text-ay-muted">
            near-miss
          </span>
        )}
      </div>
      <div className="mt-0.5 text-caption">
        <span className={cn('font-semibold', textTone)}>{verdict}</span>
        <span className="text-ay-muted"> · {dot.detail}</span>
      </div>
    </div>
  );
}

/**
 * The pre-market dead-dot check (roadmap F4 v2): per Connect-the-Dots gate input, live vs dead over
 * TODAY's newest rejections, and which are REQUIRED alive. A dead REQUIRED input silently re-caps the
 * composite; a dead OPTIONAL input just contributes nothing. Always reflects today, NOT the date filter
 * below. Secondary panel — a load/error here never blocks the rejections table.
 */
function DotHealthPanel() {
  const q = useDotHealth();
  const health = q.data;

  return (
    <section
      className="mb-4 rounded-lg border border-ay-border bg-surface-1 p-3"
      aria-label="Gate-input health (today)"
    >
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h2 className="text-sm font-semibold text-ay-text">
          Gate-input health <span className="font-normal text-ay-muted">· today</span>
        </h2>
        {health && (
          <span className="text-caption text-ay-muted">
            {health.session ? 'in session' : 'market closed'} · {health.rowsInspected} rejection
            {health.rowsInspected === 1 ? '' : 's'} inspected · as of {fmtAsOf(health.asOf)} IST
          </span>
        )}
      </div>

      {q.isLoading && <p className="text-caption text-ay-muted">Loading dot health…</p>}
      {q.isError && <p className="text-caption text-ay-muted">Dot health unavailable right now.</p>}
      {health && health.rowsInspected === 0 && (
        <p className="text-caption text-ay-muted">
          {health.rowsScanned > 0
            ? `No context-bearing rejections yet — ${health.rowsScanned} scanned, all blocked at an early rail; dot liveness can't be judged from them.`
            : "No rejections yet today — dot liveness can't be judged until the gate has evaluated a bar."}
        </p>
      )}
      {health && health.rowsInspected > 0 && (
        <>
          <div className="flex flex-wrap gap-2">
            {health.dots.map((d) => (
              <DotChip key={d.dot} dot={d} />
            ))}
          </div>
          <p className="mt-2 text-caption text-ay-muted">
            A dead <span className="font-medium text-bear">required</span> input silently caps the
            composite; a dead <span className="font-medium text-warn">optional</span> input just
            contributes nothing.
          </p>
        </>
      )}
    </section>
  );
}

const PAGE_SIZE = 200;

// The main rejections table, adopted onto the shared DataTable (each cell's content/formatting is
// preserved 1:1 from the hand-rolled table; DataTable owns the expander column + the inline detail row).
// Columns are non-sortable to match the original static-header table; the expanded breakdown rides
// renderExpanded (see RejectionDetail below), NOT a column.
const REJECTION_COLUMNS: DataColumn<SignalRejectionDto>[] = [
  {
    id: 'time',
    header: 'Time (IST)',
    align: 'left',
    render: (r) => fmtTime(r.barTime),
    cellClassName: () => 'text-ay-muted',
    mobileLabel: 'Time (IST)',
  },
  {
    id: 'strategy',
    header: 'Strategy',
    align: 'left',
    render: (r) => r.strategySlug,
    mobileLabel: 'Strategy',
  },
  {
    id: 'symbol',
    header: 'Symbol',
    align: 'left',
    render: (r) => r.tradingsymbol,
    cellClassName: () => 'text-ay-muted',
    mobileLabel: 'Symbol',
  },
  {
    id: 'side',
    header: 'Side',
    align: 'left',
    render: (r) =>
      r.side ? (
        <span className={r.side === 'CE' ? 'text-bull' : 'text-bear'}>{r.side}</span>
      ) : (
        <span className="text-ay-muted">—</span>
      ),
    mobileLabel: 'Side',
  },
  {
    id: 'rail',
    header: 'Blocking rail',
    align: 'left',
    render: (r) => (
      <span className="rounded bg-surface-2 px-1.5 py-0.5 text-xs text-ay-text">{r.blockingRail}</span>
    ),
    mobileLabel: 'Blocking rail',
  },
  {
    id: 'operand',
    header: 'Operand',
    align: 'right',
    render: (r) => fmtNum(r.blockingOperand),
    mobileLabel: 'Operand',
  },
  {
    id: 'threshold',
    header: 'Threshold',
    align: 'right',
    render: (r) => fmtNum(r.blockingThreshold),
    mobileLabel: 'Threshold',
  },
  {
    id: 'margin',
    header: 'Margin',
    align: 'right',
    render: (r) => <Margin value={r.blockingMargin} />,
    mobileLabel: 'Margin',
  },
  {
    id: 'composite',
    header: 'Composite',
    align: 'right',
    render: (r) =>
      r.compositeScore == null
        ? '—'
        : `${fmtNum(r.compositeScore)} / ${fmtNum(r.compositeThreshold)}`,
    cellClassName: () => 'text-ay-muted',
    mobileLabel: 'Composite',
  },
  {
    id: 'reason',
    header: 'Reason',
    align: 'left',
    render: (r) => r.blockingReason,
    cellClassName: () => 'text-ay-muted',
    mobileLabel: 'Reason',
  },
  {
    id: 'health',
    header: 'Inputs',
    align: 'left',
    render: (r) => <HealthBadge row={r} />,
    mobileLabel: 'Inputs',
  },
];

export function RejectionsPage() {
  const [date, setDate] = useState<string>(todayIst());
  const [rail, setRail] = useState<string>('');
  const [health, setHealth] = useState<string>('');
  const [offset, setOffset] = useState(0);

  const isToday = date === todayIst();
  const bounds = useMemo(() => dayBoundsIst(date), [date]);
  const degraded = health === '' ? null : health === 'degraded';

  const q = useSignalRejections(
    null,
    rail || null,
    bounds.from,
    bounds.to,
    PAGE_SIZE,
    offset,
    degraded,
  );
  const counts = useRejectionRailCounts(null, bounds.from, bounds.to);
  const shadow = useShadowSummary(bounds.from, bounds.to);
  const rows = q.data?.items ?? [];
  const railOptions = counts.data?.items ?? [];
  const shadowBooks = shadow.data?.items ?? [];

  return (
    <LoadBeat>
      <PageHeader
        title="Signal Rejections"
        subtitle="Every scalper entry the live confluence gate blocked — the failing rail, its margin, and the full breakdown"
        help="When a scalper's chart entry fires but the §12.3 confluence gate returns no decision, the entry is blocked and recorded here. Each row shows the FIRST failing rail and how far its operand sat from the threshold; expand a row for the rail-by-rail checklist, the dot-by-dot confluence score, and the raw OI/macro/chart inputs. Live-only — backtests never run the gate."
      />

      <DotHealthPanel />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <DateInput
          value={date}
          ariaLabel="Rejection day"
          onChange={(v) => {
            setDate(v ?? todayIst());
            setOffset(0);
          }}
        />
        {isToday && <span className="text-xs text-ay-muted">live (today)</span>}
        <Select
          value={rail}
          ariaLabel="Filter by blocking rail"
          onChange={(v) => {
            setRail(v);
            setOffset(0);
          }}
          options={[
            { value: '', label: 'All rails' },
            ...railOptions.map((c) => ({ value: c.rail, label: `${c.rail} (${c.count})` })),
          ]}
        />
        <Select
          value={health}
          ariaLabel="Filter by gate-input health"
          onChange={(v) => {
            setHealth(v);
            setOffset(0);
          }}
          options={[
            { value: '', label: 'Any input health' },
            { value: 'degraded', label: 'Degraded inputs only' },
            { value: 'clean', label: 'Healthy inputs only' },
          ]}
        />
      </div>

      {/* Shadow-book league — champion vs challenger variants on this day's rejections */}
      {shadowBooks.length > 0 && (
        <div className="mb-3 flex flex-wrap gap-2" data-testid="shadow-league">
          {shadowBooks.map((b) => {
            const pnl = b.pnlPoints == null ? NaN : Number(b.pnlPoints);
            const net = b.pnlNet == null ? NaN : Number(b.pnlNet);
            return (
              <div key={b.variant} className="rounded-md border px-2 py-1 text-xs">
                <span className="font-semibold text-ay-text">{b.variant}</span>{' '}
                <span className="text-ay-muted">
                  {b.open} open · {b.closed} closed · {b.wins}W/{b.losses}L ·
                </span>{' '}
                {!Number.isNaN(net) && (
                  <span className={net < 0 ? 'text-bear' : 'text-bull'}>
                    {net >= 0 ? '+' : ''}₹{fmtNum(b.pnlNet, 2)} net ·
                  </span>
                )}{' '}
                {b.unpriced > 0 && (
                  <span className="text-ay-muted" title="closed rows with no cost-model net — excluded from the ₹ net">
                    {b.unpriced} unpriced ·
                  </span>
                )}{' '}
                <span className={Number.isNaN(pnl) ? 'text-ay-muted' : pnl < 0 ? 'text-bear' : 'text-bull'}>
                  {Number.isNaN(pnl) ? '—' : `${pnl >= 0 ? '+' : ''}${fmtNum(b.pnlPoints, 2)} pts`}
                </span>
              </div>
            );
          })}
        </div>
      )}

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
            <DataTable
              columns={REJECTION_COLUMNS}
              rows={rows}
              rowKey={(r) => String(r.id)}
              ariaLabel="Signal rejections"
              maxHeight="70vh"
              renderExpanded={(r) => <RejectionDetail row={r} />}
            />
            <p className="mt-2 text-xs text-ay-muted">
              Expand any row (the ▸ toggle is keyboard-accessible) for the full breakdown. {rows.length} blocked{' '}
              {rows.length === 1 ? 'entry' : 'entries'}
              {rail ? ` for rail “${rail}”` : ''}{offset > 0 ? ` (from row ${offset + 1})` : ''}.
            </p>
            <Pager offset={offset} limit={PAGE_SIZE} count={rows.length} onOffset={setOffset} />
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
                  {/* WCAG 1.4.1: a non-colour cue for supports vs opposes — a ▲/▼ shape for sighted
                      colour-blind users + an sr-only word for AT (colour alone is not sufficient). */}
                  <span aria-hidden="true">{dot.supports ? '▲' : '▼'} </span>
                  <span className="ay-sr-only">{dot.supports ? 'Supports — ' : 'Opposes — '}</span>
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

      {/* Raw context, preceded by the per-row input verdict that qualifies it */}
      <section>
        <h3 className="mb-1 text-xs font-semibold uppercase text-ay-muted">Input health</h3>
        <HealthDetail health={row.dataHealth} />

        <h3 className="mb-1 mt-3 text-xs font-semibold uppercase text-ay-muted">Context</h3>
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

/**
 * The expanded per-row input verdict: exactly which gate inputs were ABSENT when this bar was scored,
 * so the context values beside it can be read with the right amount of trust. Names the by-design S24
 * expiry case explicitly rather than hiding it — an inert OI block on the root's own monthly expiry
 * is not an outage, and a reader who does not know that mis-diagnoses the whole session.
 */
function HealthDetail({ health }: { health?: RejectionDataHealth | null }) {
  if (!health) {
    return (
      <p className="text-xs text-ay-muted">
        Not recorded — this row predates per-row input health. It was never judged, so treat it as
        unknown rather than healthy.
      </p>
    );
  }
  if (!health.contextBearing) {
    return (
      <p className="text-xs text-ay-muted">
        Blocked before the chain fetch, so no gate inputs were read — nothing to judge.
      </p>
    );
  }
  return (
    <div className="text-xs">
      <p className={health.degraded ? 'text-warn' : 'text-ay-muted'}>
        {health.degraded
          ? `${health.flags.length} gate input${health.flags.length === 1 ? '' : 's'} absent when this bar was scored`
          : 'Every gate input was present.'}
      </p>
      {health.flags.length > 0 && (
        <ul className="mt-1 flex flex-wrap gap-1">
          {health.flags.map((f) => (
            <li key={f} className="rounded bg-warn/15 px-1.5 py-0.5 text-[11px] text-warn">
              {f}
            </li>
          ))}
        </ul>
      )}
      {health.oiSuppressed && (
        <p className="mt-1 text-ay-muted">
          OI reads are inert <span className="font-medium">by design</span> on this root’s monthly
          index expiry (S24) — not counted against the row.
        </p>
      )}
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
