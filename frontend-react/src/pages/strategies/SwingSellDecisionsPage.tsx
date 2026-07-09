import { useState } from 'react';
import { m } from 'motion/react';
import { formatDecimal, isNegative } from '../../lib/decimal.ts';
import { cn } from '../../lib/cn.ts';
import { PageHeader } from '../../components/PageHeader.tsx';
import { QueryState } from '../../components/QueryState.tsx';
import { Skeleton } from '../../components/Skeletons.tsx';
import { BeatBlock, LoadBeat } from '../../components/LoadBeat.tsx';
import {
  useSwingSellDecisions,
  type SwingFamily,
  type SwingSellDecision,
} from '../../api/swing.ts';

// /strategies/swing-sell-decisions (audit M20): the daily HOLD/SELL triad for the two EOD swing books.
// For every open Minervini / Manas Arora swing position it shows the "would I buy it now / why am I
// holding / where am I a seller" read — recomputed server-side on each held anchor's fresh daily series.
// Read-only: it never moves the book; the owner reads it to manage the holdings after the EOD batch.
// Replaces the curl-only /sell-decisions workflow.

const FAMILIES: { key: SwingFamily; label: string }[] = [
  { key: 'minervini', label: 'Minervini' },
  { key: 'manas-arora', label: 'Manas Arora' },
];

export function SwingSellDecisionsPage() {
  const [family, setFamily] = useState<SwingFamily>('minervini');
  const query = useSwingSellDecisions(family);
  const data = query.data;
  const sellingNow = data?.items.filter((d) => d.sellingNow).length ?? 0;

  return (
    <LoadBeat>
      <PageHeader
        title="Swing sell decisions"
        subtitle="Daily HOLD / SELL triad for the Minervini + Manas Arora swing books"
        help="For every open swing holding: would I buy it now (the entry gate re-run on today's bar), where my exits sit (the base stop + the current trail), and whether the frozen exit doctrine fires a SELL today. Recomputed after the EOD swing batch (~20:00 IST); read-only — it never moves the book."
        right={
          data ? (
            <span className="text-caption text-ay-muted">
              {data.items.length} holding · {sellingNow} selling · as of {fmtAsOf(data.asOf)}
            </span>
          ) : undefined
        }
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
      </div>

      <m.div
        key={family}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.15 }}
      >
        <QueryState
          query={query}
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
                    <SellRow key={d.symbol} d={d} />
                  ))}
                </tbody>
              </table>
            </BeatBlock>
          )}
        </QueryState>
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

/** The emitting setup + the family-specific qualifier (Minervini stage / Manas setup type). */
function setupLabel(d: SwingSellDecision): string {
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
