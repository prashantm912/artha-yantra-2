import { useEffect, useMemo, useState } from 'react';
import { cn } from '../lib/cn.ts';
import { formatDecimal } from '../lib/decimal.ts';
import type { SpurtRow } from '../api/types.ts';
import { ValueDeltaCell } from './atoms/ValueDeltaCell.tsx';
import { isStrong } from './spurtStrength.ts';

// One OI-Spurt quadrant (oipulse OI Spurt §20.3): a single OI-action bucket's strikes, sorted by
// |ΔOI| desc, paginated 7/page. The strength filter (%ΔLTP>50 AND %ΔOI>50, in spurtStrength.ts) bolds
// the qualifying rows (oipulse's "this is actually a signal" rule). Decimal strings — never parseFloat.

const PAGE = 7;
const num = (n: number | null | undefined) => (n != null ? n.toLocaleString('en-IN') : '—');
const dec = (v: string | null | undefined, d: number) => (v ? formatDecimal(v, d) : '—');

interface SpurtQuadrantProps {
  title: string;
  subtitle: string;
  rows: SpurtRow[];
}

export function SpurtQuadrant({ title, subtitle, rows }: SpurtQuadrantProps) {
  const sorted = useMemo(
    () => [...rows].sort((a, b) => Math.abs(b.oiChange) - Math.abs(a.oiChange)),
    [rows],
  );
  const [page, setPage] = useState(0);
  // Reset to the first page whenever the (search-filtered) row set changes.
  useEffect(() => setPage(0), [sorted.length]);

  const pages = Math.max(1, Math.ceil(sorted.length / PAGE));
  const clamped = Math.min(page, pages - 1);
  const start = clamped * PAGE;
  const slice = sorted.slice(start, start + PAGE);

  return (
    <div className="rounded border border-ay-border bg-surface-1">
      <div className="flex items-baseline justify-between border-b border-ay-border px-3 py-2">
        <div>
          <div className="text-sm font-semibold text-ay-text">{title}</div>
          <div className="text-xs text-ay-muted">{subtitle}</div>
        </div>
        <div className="text-xs text-ay-muted tabular-nums">{sorted.length} strikes</div>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-xs">
          <thead className="text-ay-muted">
            <tr className="border-b border-ay-border">
              <th scope="col" className="px-2 py-1 text-left font-medium">Strike</th>
              <th scope="col" className="px-2 py-1 text-left font-medium">Type</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">LTP</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Prev. Close</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">% Chng. LTP</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">% Chng. OI</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">New OI</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Old OI</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">OI Chng.</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Volume</th>
            </tr>
          </thead>
          <tbody>
            {slice.map((r) => {
              const oldOi = r.oi != null ? r.oi - r.oiChange : null;
              const strong = isStrong(r);
              return (
                <tr
                  key={`${r.strike}-${r.optionType}`}
                  className={cn('border-t border-ay-border text-ay-text', strong && 'font-semibold')}
                >
                  <td className="px-2 py-1 text-left tabular-nums">
                    {r.strike}
                    {strong && <span className="ay-sr-only"> signal-strength qualifying</span>}
                  </td>
                  <td className="px-2 py-1 text-left">{r.optionType}</td>
                  <td className="px-2 py-1 text-right tabular-nums">{dec(r.ltp, 2)}</td>
                  <td className="px-2 py-1 text-right tabular-nums text-ay-muted">{dec(r.prevLtp, 2)}</td>
                  <td className="px-2 py-1 text-right"><ValueDeltaCell value={r.ltpChangePct} suffix="%" /></td>
                  <td className="px-2 py-1 text-right"><ValueDeltaCell value={r.spurtPct} suffix="%" /></td>
                  <td className="px-2 py-1 text-right tabular-nums">{num(r.oi)}</td>
                  <td className="px-2 py-1 text-right tabular-nums text-ay-muted">{num(oldOi)}</td>
                  <td className={cn('px-2 py-1 text-right tabular-nums', r.oiChange > 0 ? 'text-bull' : r.oiChange < 0 ? 'text-bear' : '')}>
                    {r.oiChange > 0 ? '+' : ''}
                    {r.oiChange.toLocaleString('en-IN')}
                  </td>
                  <td className="px-2 py-1 text-right tabular-nums">{num(r.volume)}</td>
                </tr>
              );
            })}
            {sorted.length === 0 && (
              <tr>
                <td colSpan={10} className="px-2 py-3 text-center text-ay-muted">
                  No strikes.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {sorted.length > PAGE && (
        <div className="flex items-center justify-between border-t border-ay-border px-3 py-1.5 text-xs text-ay-muted">
          <span className="tabular-nums">
            {start + 1}–{Math.min(start + PAGE, sorted.length)} of {sorted.length}
          </span>
          <span className="flex gap-1">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={clamped === 0}
              className="rounded border border-ay-border px-2 py-0.5 hover:border-accent disabled:opacity-40"
            >
              Prev
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => Math.min(pages - 1, p + 1))}
              disabled={clamped >= pages - 1}
              className="rounded border border-ay-border px-2 py-0.5 hover:border-accent disabled:opacity-40"
            >
              Next
            </button>
          </span>
        </div>
      )}
    </div>
  );
}
