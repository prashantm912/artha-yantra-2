import { useEffect, useMemo, useRef, useState, type MouseEvent } from 'react';
import { m } from 'motion/react';
import { Link } from 'react-router-dom';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { isMarketHoursIst } from '../../lib/marketHours.ts';
import { Button } from '../../components/atoms/Button.tsx';
import { DataTable, type DataColumn } from '../../components/DataTable.tsx';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog.tsx';
import {
  useAckSellDecision,
  useRecordedSellDecisions,
  useRunSwingBatch,
  useSwingSellDecisions,
  type RecordedSellDecision,
  type SwingFamily,
  type SwingSellDecision,
} from '../../api/swing.ts';

// /strategies/swing-sell-decisions (audit M20 + §7.2.4): the daily HOLD/SELL triad for the two EOD swing
// books. For every open Minervini / Manas Arora swing position it shows the "would I buy it now / why am
// I holding / where am I a seller" read. Two views: LIVE recomputes each held anchor's fresh daily series
// server-side; RECORDED reads the durable snapshot the 20:05 batch persists (V037), with an acknowledge
// affordance + a deep-link to the paper book. Those tables stay read-only; the explicit confirmed run
// controls are the sole mutation here and can emit real entries/exits.

const FAMILIES: { key: SwingFamily; label: string }[] = [
  { key: 'minervini', label: 'Minervini' },
  { key: 'manas-arora', label: 'Manas Arora' },
];

const MARKET_HOURS_DISABLED_LABEL = 'Disabled during market hours (09:15–15:30 IST)';
const SWING_RUN_WARNING =
  'Runs on CURRENT inputs — this is NOT an as-of replay of a past session. The armed 08:35 catch-up is the primary recovery path; use this only when swing_catchup_runs shows ABANDONED/refused.';

type Setupish = { setup: string | null; stage?: number | null; setupType?: string | null };

/** The Setup cell — the emitting setup plus the optional footprint qualifier. */
function SetupCell({ d }: { d: Setupish & { footprint?: string | null } }) {
  return (
    <>
      {setupLabel(d)}
      {d.footprint && <span className="ml-1 text-[11px] text-ay-muted/70">{d.footprint}</span>}
    </>
  );
}

/** The HOLD / SELL verdict pill. */
function VerdictCell({ d }: { d: { sellingNow: boolean; verdict: string } }) {
  return d.sellingNow ? (
    <span className="rounded bg-bear/20 px-1.5 py-0.5 text-[11px] font-semibold text-bear">
      {d.verdict}
    </span>
  ) : (
    <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] font-medium text-ay-muted">
      HOLD
    </span>
  );
}

/** The trail level, muted, with the not-armed-yet hint kept on hover. The hint fires exactly when
 *  trailLevel is null — i.e. when the text is just '—' — so the span spans the full cell width
 *  (`block`), keeping the padded hover target the original <td> had. */
function TrailCell({ trailLevel }: { trailLevel: string | null }) {
  return (
    <span className="block" title={trailLevel == null ? 'Trail not armed yet' : undefined}>
      {money(trailLevel)}
    </span>
  );
}

/** The acknowledge affordance on a recorded decision — a per-row mutation. */
function AckCell({ d, family }: { d: RecordedSellDecision; family: SwingFamily }) {
  const ack = useAckSellDecision(family);
  return d.acknowledgedAt != null ? (
    <span
      className="rounded bg-bull/15 px-1.5 py-0.5 text-[11px] font-medium text-bull"
      title={`Acknowledged ${fmtAsOf(d.acknowledgedAt ?? '')}`}
    >
      ✓ Acknowledged
    </span>
  ) : (
    <button
      type="button"
      onClick={() => ack.mutate(d.id)}
      disabled={ack.isPending}
      title="Mark this decision as seen/acted on."
      className="rounded-md border border-ay-border px-2 py-0.5 text-[11px] font-medium text-ay-text hover:border-accent disabled:opacity-50"
    >
      {ack.isPending ? '…' : 'Acknowledge'}
    </button>
  );
}

export function SwingSellDecisionsPage() {
  const [family, setFamily] = useState<SwingFamily>('minervini');
  const [view, setView] = useState<'live' | 'recorded'>('live');
  const [pendingRunFamily, setPendingRunFamily] = useState<SwingFamily | null>(null);
  const [marketHours, setMarketHours] = useState(() => isMarketHoursIst());
  const runTriggerRef = useRef<HTMLButtonElement | null>(null);
  const live = useSwingSellDecisions(family, view === 'live');
  const recorded = useRecordedSellDecisions(family, view === 'recorded');
  const run = useRunSwingBatch();

  useEffect(() => {
    const refreshMarketHours = () => {
      const disabled = isMarketHoursIst();
      setMarketHours(disabled);
      if (disabled) setPendingRunFamily(null);
    };
    const interval = window.setInterval(refreshMarketHours, 30_000);
    return () => window.clearInterval(interval);
  }, []);

  const requestRun = (nextFamily: SwingFamily, event: MouseEvent<HTMLButtonElement>) => {
    if (isMarketHoursIst()) {
      setMarketHours(true);
      return;
    }
    runTriggerRef.current = event.currentTarget;
    setPendingRunFamily(nextFamily);
  };

  const confirmRun = () => {
    if (pendingRunFamily == null) return;
    if (isMarketHoursIst()) {
      setMarketHours(true);
      setPendingRunFamily(null);
      return;
    }
    run.mutate(pendingRunFamily);
    setPendingRunFamily(null);
  };

  const liveColumns: DataColumn<SwingSellDecision>[] = useMemo(
    () => [
      {
        id: 'symbol',
        header: 'Symbol',
        align: 'left',
        mobileLabel: 'Symbol',
        cellClassName: () => 'font-medium text-ay-text',
        render: (d) => d.symbol,
      },
      {
        id: 'setup',
        header: 'Setup',
        align: 'left',
        mobileLabel: 'Setup',
        cellClassName: () => 'text-ay-muted',
        render: (d) => <SetupCell d={d} />,
      },
      { id: 'entry', header: 'Entry', mobileLabel: 'Entry', render: (d) => money(d.entryPrice) },
      {
        id: 'current',
        header: 'Current',
        mobileLabel: 'Current',
        render: (d) => money(d.currentPrice),
      },
      {
        id: 'unrealized',
        header: 'Unrealized',
        mobileLabel: 'Unrealized',
        cellClassName: (d) => pnlTone(d.unrealizedPct),
        render: (d) => signedPct(d.unrealizedPct),
      },
      { id: 'stop', header: 'Stop', mobileLabel: 'Stop', render: (d) => money(d.stopLevel) },
      {
        id: 'trail',
        header: 'Trail',
        mobileLabel: 'Trail',
        cellClassName: () => 'text-ay-muted',
        render: (d) => <TrailCell trailLevel={d.trailLevel} />,
      },
      {
        id: 'buyable',
        header: 'Buy now?',
        align: 'left',
        mobileLabel: 'Buy now?',
        render: (d) =>
          d.stillBuyable ? (
            <span
              className="rounded bg-bull/20 px-1.5 py-0.5 text-[11px] font-medium text-bull"
              title="The entry gate still passes on today's bar"
            >
              Buyable
            </span>
          ) : (
            <span className="text-ay-muted/60">—</span>
          ),
      },
      {
        id: 'verdict',
        header: 'Verdict',
        align: 'left',
        mobileLabel: 'Verdict',
        render: (d) => <VerdictCell d={d} />,
      },
    ],
    [],
  );

  const recordedColumns: DataColumn<RecordedSellDecision>[] = useMemo(
    () => [
      {
        id: 'date',
        header: 'Date',
        align: 'left',
        mono: true,
        mobileLabel: 'Date',
        cellClassName: () => 'text-ay-muted',
        render: (d) => d.runDate,
      },
      {
        id: 'symbol',
        header: 'Symbol',
        align: 'left',
        mobileLabel: 'Symbol',
        cellClassName: () => 'font-medium',
        render: (d) => (
          <Link
            to={`/paper/${d.book}`}
            className="text-ay-text hover:text-accent hover:underline"
            title={`Open the ${family} paper book (position from signal #${d.signalId})`}
          >
            {d.symbol}
          </Link>
        ),
      },
      {
        id: 'setup',
        header: 'Setup',
        align: 'left',
        mobileLabel: 'Setup',
        cellClassName: () => 'text-ay-muted',
        render: (d) => <SetupCell d={d} />,
      },
      {
        id: 'unrealized',
        header: 'Unrealized',
        mobileLabel: 'Unrealized',
        cellClassName: (d) => pnlTone(d.unrealizedPct),
        render: (d) => signedPct(d.unrealizedPct),
      },
      { id: 'stop', header: 'Stop', mobileLabel: 'Stop', render: (d) => money(d.stopLevel) },
      {
        id: 'trail',
        header: 'Trail',
        mobileLabel: 'Trail',
        cellClassName: () => 'text-ay-muted',
        render: (d) => <TrailCell trailLevel={d.trailLevel} />,
      },
      {
        id: 'verdict',
        header: 'Verdict',
        align: 'left',
        mobileLabel: 'Verdict',
        render: (d) => <VerdictCell d={d} />,
      },
      {
        id: 'ack',
        header: 'Acknowledge',
        align: 'left',
        mobileLabel: 'Acknowledge',
        render: (d) => <AckCell d={d} family={family} />,
      },
    ],
    [family],
  );
  const sellingNow = live.data?.items.filter((d) => d.sellingNow).length ?? 0;
  const unacked = recorded.data?.items.filter((d) => d.acknowledgedAt == null).length ?? 0;

  const right =
    view === 'live'
      ? live.data
        ? (
            <span className="text-caption text-ay-muted">
              {live.data.items.length} holding · {sellingNow} selling · as of {fmtAsOf(live.data.asOf)}
            </span>
          )
        : undefined
      : recorded.data
        ? (
            <span className="text-caption text-ay-muted">
              {recorded.data.items.length} recorded · {unacked} unacknowledged
            </span>
          )
        : undefined;

  return (
    <LoadBeat>
      <PageHeader
        title="Swing sell decisions"
        subtitle="Daily HOLD / SELL triad for the Minervini + Manas Arora swing books"
        help="For every open swing holding: would I buy it now (the entry gate re-run on today's bar), where my exits sit (the base stop + the current trail), and whether the frozen exit doctrine fires a SELL today. Live recomputes on read; Recorded is the durable snapshot the EOD swing batch (~20:00 IST) persists, which you can acknowledge. Those views are read-only; the confirmed Run batch now controls are the only actions here that can emit real entries or exits."
        right={right}
      />

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <div
          role="tablist"
          aria-label="Swing book"
          className="flex rounded-md border border-ay-border p-0.5"
        >
          {FAMILIES.map((f) => (
            <button
              key={f.key}
              type="button"
              role="tab"
              aria-selected={family === f.key}
              onClick={() => setFamily(f.key)}
              className={cn(
                'h-8 rounded px-3 text-sm',
                family === f.key ? 'bg-accent text-surface-0' : 'text-ay-text hover:bg-surface-2',
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Run swing batch">
          {FAMILIES.map((f) => {
            const disabled = marketHours || run.isPending;
            const descriptionId = `${f.key}-run-disabled-reason`;
            const title = marketHours
              ? MARKET_HOURS_DISABLED_LABEL
              : run.isPending
                ? 'Disabled while a swing batch is in flight.'
                : `Run the ${f.label} swing batch with current inputs.`;
            return (
              <div key={f.key} className="flex flex-wrap items-center gap-2">
                <span className="text-xs font-medium text-ay-muted">{f.label}</span>
                <Button
                  type="button"
                  aria-describedby={marketHours ? descriptionId : undefined}
                  disabled={disabled}
                  loading={run.isPending && run.variables === f.key}
                  onClick={(event) => requestRun(f.key, event)}
                  title={title}
                  variant="primary"
                  size="sm"
                >
                  Run {f.label} batch now
                </Button>
                {marketHours && (
                  <span id={descriptionId} className="text-xs text-ay-muted">
                    {MARKET_HOURS_DISABLED_LABEL}
                  </span>
                )}
              </div>
            );
          })}
        </div>
        <div
          className="inline-flex rounded-md border border-ay-border"
          role="group"
          aria-label="Live or recorded sell decisions"
        >
          <button
            type="button"
            onClick={() => setView('live')}
            aria-pressed={view === 'live'}
            title="Recompute the triad now over each held anchor's fresh daily series."
            className={cn(
              'h-8 rounded-l-md px-3 text-sm',
              view === 'live' ? 'bg-accent/15 font-semibold text-accent' : 'text-ay-muted hover:text-ay-text',
            )}
          >
            Live
          </button>
          <button
            type="button"
            onClick={() => setView('recorded')}
            aria-pressed={view === 'recorded'}
            title="The durable per-day snapshot the EOD batch persisted — acknowledge a decision here."
            className={cn(
              'h-8 rounded-r-md border-l border-ay-border px-3 text-sm',
              view === 'recorded' ? 'bg-accent/15 font-semibold text-accent' : 'text-ay-muted hover:text-ay-text',
            )}
          >
            Recorded
          </button>
        </div>
      </div>

      {pendingRunFamily != null && (
        <Dialog
          open
          onOpenChange={(open) => {
            if (!open) setPendingRunFamily(null);
          }}
        >
          <DialogContent
            onCloseAutoFocus={(event) => {
              event.preventDefault();
              runTriggerRef.current?.focus();
            }}
          >
            <DialogHeader>
              <DialogTitle>
                Run {FAMILIES.find((f) => f.key === pendingRunFamily)?.label} batch now?
              </DialogTitle>
              <DialogDescription>{SWING_RUN_WARNING}</DialogDescription>
            </DialogHeader>
            <p className="text-sm text-ay-muted">
              This endpoint performs a real current-input re-run every time it is called. The server-side
              per-family mutex prevents concurrent runs, but a second same-day call is not a no-op.
            </p>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setPendingRunFamily(null)}>
                Cancel
              </Button>
              <Button type="button" onClick={confirmRun} loading={run.isPending}>
                Confirm &amp; run
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}

      <m.div
        key={`${family}:${view}`}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.15 }}
      >
        {view === 'live' ? (
          <QueryState
            query={live}
            empty={{ title: 'No open swing holdings for this book.' }}
            errorTitle="Couldn't load the sell decisions"
            skeleton={<Skeleton variant="table-rows" rows={8} cols={9} />}
          >
            {(report) => (
              <BeatBlock>
                <DataTable
                  columns={liveColumns}
                  rows={report.items}
                  rowKey={(d) => String(d.signalId)}
                  ariaLabel="Live swing sell decisions"
                />
              </BeatBlock>
            )}
          </QueryState>
        ) : (
          <QueryState
            query={recorded}
            isEmpty={(r) => r.items.length === 0}
            empty={{ title: 'No recorded sell decisions yet — the EOD batch writes them after ~20:00 IST.' }}
            errorTitle="Couldn't load the recorded decisions"
            skeleton={<Skeleton variant="table-rows" rows={8} cols={8} />}
          >
            {(report) => (
              <BeatBlock>
                <DataTable
                  columns={recordedColumns}
                  rows={report.items}
                  rowKey={(d) => String(d.id)}
                  ariaLabel="Recorded swing sell decisions"
                />
              </BeatBlock>
            )}
          </QueryState>
        )}
      </m.div>
    </LoadBeat>
  );
}

/** The emitting setup + the family-specific qualifier (Minervini stage / Manas setup type). */
function setupLabel(d: Setupish): string {
  const parts = [d.setup ?? '—'];
  if (d.stage != null) parts.push(`Stage ${d.stage}`);
  if (d.setupType) parts.push(d.setupType);
  return parts.join(' · ');
}

function money(v: string | null): string {
  return v == null ? '—' : formatDecimal(v, 2);
}

/** A signed percent (the value already carries a leading '-' when negative). */
function signedPct(v: string | null): string {
  if (v == null) return '—';
  const sign = !isNegative(v) && formatDecimal(v, 2) !== '0.00' ? '+' : '';
  return `${sign}${formatDecimal(v, 2)}%`;
}

function pnlTone(v: string | null): string {
  if (v == null) return '';
  return isNegative(v) ? 'text-bear' : 'text-bull';
}

/** asOf already carries the +05:30 offset — show the wall-clock date+time as-is (IST), no re-parse. */
function fmtAsOf(iso: string): string {
  return iso.length >= 16 ? `${iso.slice(0, 10)} ${iso.slice(11, 16)} IST` : iso;
}
