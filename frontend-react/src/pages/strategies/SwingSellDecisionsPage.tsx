import { useState } from 'react';
import { m } from 'motion/react';
import { Link } from 'react-router-dom';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  useAckSellDecision,
  useRecordedSellDecisions,
  useSwingSellDecisions,
  type RecordedSellDecision,
  type SwingFamily,
  type SwingSellDecision,
} from '../../api/swing.ts';

// /strategies/swing-sell-decisions (audit M20 + §7.2.4): the daily HOLD/SELL triad for the two EOD swing
// books. For every open Minervini / Manas Arora swing position it shows the "would I buy it now / why am
// I holding / where am I a seller" read. Two views: LIVE recomputes each held anchor's fresh daily series
// server-side; RECORDED reads the durable snapshot the 20:05 batch persists (V037), with an acknowledge
// affordance + a deep-link to the paper book. Read-only otherwise — it never moves the book.

const FAMILIES: { key: SwingFamily; label: string }[] = [
  { key: 'minervini', label: 'Minervini' },
  { key: 'manas-arora', label: 'Manas Arora' },
];

type Setupish = { setup: string | null; stage?: number | null; setupType?: string | null };

export function SwingSellDecisionsPage() {
  const [family, setFamily] = useState<SwingFamily>('minervini');
  const [view, setView] = useState<'live' | 'recorded'>('live');
  const live = useSwingSellDecisions(family, view === 'live');
  const recorded = useRecordedSellDecisions(family, view === 'recorded');
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
        help="For every open swing holding: would I buy it now (the entry gate re-run on today's bar), where my exits sit (the base stop + the current trail), and whether the frozen exit doctrine fires a SELL today. Live recomputes on read; Recorded is the durable snapshot the EOD swing batch (~20:00 IST) persists, which you can acknowledge. Read-only — it never moves the book."
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
              <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
                <table className="w-full border-collapse text-sm">
                  <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                    <tr>
                      <th className="px-2 py-2 font-medium">Symbol</th>
                      <th className="px-2 py-2 font-medium">Setup</th>
                      <th className="px-2 py-2 text-right font-medium">Entry</th>
                      <th className="px-2 py-2 text-right font-medium">Current</th>
                      <th className="px-2 py-2 text-right font-medium">Unrealized</th>
                      <th className="px-2 py-2 text-right font-medium">Stop</th>
                      <th className="px-2 py-2 text-right font-medium">Trail</th>
                      <th className="px-2 py-2 font-medium">Buy now?</th>
                      <th className="px-2 py-2 font-medium">Verdict</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.items.map((d) => (
                      <SellRow key={d.signalId} d={d} />
                    ))}
                  </tbody>
                </table>
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
              <BeatBlock className="overflow-auto rounded-lg border border-ay-border">
                <table className="w-full border-collapse text-sm">
                  <thead className="bg-surface-1 text-left text-xs uppercase text-ay-muted">
                    <tr>
                      <th className="px-2 py-2 font-medium">Date</th>
                      <th className="px-2 py-2 font-medium">Symbol</th>
                      <th className="px-2 py-2 font-medium">Setup</th>
                      <th className="px-2 py-2 text-right font-medium">Unrealized</th>
                      <th className="px-2 py-2 text-right font-medium">Stop</th>
                      <th className="px-2 py-2 text-right font-medium">Trail</th>
                      <th className="px-2 py-2 font-medium">Verdict</th>
                      <th className="px-2 py-2 font-medium">Acknowledge</th>
                    </tr>
                  </thead>
                  <tbody>
                    {report.items.map((d) => (
                      <RecordedRow key={d.id} d={d} family={family} />
                    ))}
                  </tbody>
                </table>
              </BeatBlock>
            )}
          </QueryState>
        )}
      </m.div>
    </LoadBeat>
  );
}

function SellRow({ d }: { d: SwingSellDecision }) {
  return (
    <tr className="border-t border-ay-border">
      <td className="px-2 py-2 font-medium text-ay-text">{d.symbol}</td>
      <td className="px-2 py-2 text-ay-muted">
        {setupLabel(d)}
        {d.footprint && <span className="ml-1 text-[11px] text-ay-muted/70">{d.footprint}</span>}
      </td>
      <td className="px-2 py-2 text-right tabular-nums">{money(d.entryPrice)}</td>
      <td className="px-2 py-2 text-right tabular-nums">{money(d.currentPrice)}</td>
      <td className={cn('px-2 py-2 text-right tabular-nums', pnlTone(d.unrealizedPct))}>
        {signedPct(d.unrealizedPct)}
      </td>
      <td className="px-2 py-2 text-right tabular-nums">{money(d.stopLevel)}</td>
      <td
        className="px-2 py-2 text-right tabular-nums text-ay-muted"
        title={d.trailLevel == null ? 'Trail not armed yet' : undefined}
      >
        {money(d.trailLevel)}
      </td>
      <td className="px-2 py-2">
        {d.stillBuyable ? (
          <span
            className="rounded bg-bull/20 px-1.5 py-0.5 text-[11px] font-medium text-bull"
            title="The entry gate still passes on today's bar"
          >
            Buyable
          </span>
        ) : (
          <span className="text-ay-muted/60">—</span>
        )}
      </td>
      <td className="px-2 py-2">
        {d.sellingNow ? (
          <span className="rounded bg-bear/20 px-1.5 py-0.5 text-[11px] font-semibold text-bear">
            {d.verdict}
          </span>
        ) : (
          <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] font-medium text-ay-muted">
            HOLD
          </span>
        )}
      </td>
    </tr>
  );
}

function RecordedRow({ d, family }: { d: RecordedSellDecision; family: SwingFamily }) {
  const ack = useAckSellDecision(family);
  const acked = d.acknowledgedAt != null;
  return (
    <tr className="border-t border-ay-border">
      <td className="px-2 py-2 tabular-nums text-ay-muted">{d.runDate}</td>
      <td className="px-2 py-2 font-medium">
        <Link
          to={`/paper/${d.book}`}
          className="text-ay-text hover:text-accent hover:underline"
          title={`Open the ${family} paper book (position from signal #${d.signalId})`}
        >
          {d.symbol}
        </Link>
      </td>
      <td className="px-2 py-2 text-ay-muted">
        {setupLabel(d)}
        {d.footprint && <span className="ml-1 text-[11px] text-ay-muted/70">{d.footprint}</span>}
      </td>
      <td className={cn('px-2 py-2 text-right tabular-nums', pnlTone(d.unrealizedPct))}>
        {signedPct(d.unrealizedPct)}
      </td>
      <td className="px-2 py-2 text-right tabular-nums">{money(d.stopLevel)}</td>
      <td
        className="px-2 py-2 text-right tabular-nums text-ay-muted"
        title={d.trailLevel == null ? 'Trail not armed yet' : undefined}
      >
        {money(d.trailLevel)}
      </td>
      <td className="px-2 py-2">
        {d.sellingNow ? (
          <span className="rounded bg-bear/20 px-1.5 py-0.5 text-[11px] font-semibold text-bear">
            {d.verdict}
          </span>
        ) : (
          <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11px] font-medium text-ay-muted">
            HOLD
          </span>
        )}
      </td>
      <td className="px-2 py-2">
        {acked ? (
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
        )}
      </td>
    </tr>
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
