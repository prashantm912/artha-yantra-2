import { useMemo, useState } from 'react';
import { bucketWindow } from '../api/oiAnalysisFold.ts';
import { oiIntMeta, type OiInterpretation } from '../core/oiInterpretation.ts';
import { OiBadge4 } from './atoms/OiBadge4.tsx';
import { ValueDeltaCell } from './atoms/ValueDeltaCell.tsx';
import type { BankAnalysisCell, BankAnalysisRow } from '../api/types.ts';

// oipulse "Banks Analysis" matrix (§futures/banks-analysis): rows = descending intervals, columns = the 6
// banks. Each cell = (LTP% / OI%) cumulative-from-day-open + the per-interval 4-state OI badge. Raw
// scrollable <table> (the dense-matrix archetype, like ConnectingDots) + client pagination (25) + Legend.
// Decimal-string %s render via ValueDeltaCell (signed, never parseFloat); the badge carries the colour cue.

const PAGE_SIZE = 25;
const STATES: OiInterpretation[] = ['LONG_BUILDUP', 'SHORT_BUILDUP', 'SHORT_COVERING', 'LONG_UNWINDING'];

/** One bank cell: (LTP% / OI%) stacked over the per-interval interpretation badge; null → muted dash. */
function BankCell({ cell }: { cell: BankAnalysisCell | null }) {
  if (!cell) {
    return <span className="text-ay-muted">—</span>;
  }
  return (
    <div className="flex flex-col items-center gap-0.5">
      <span className="inline-flex items-center gap-0.5 tabular-nums">
        <ValueDeltaCell value={cell.ltpPct} suffix="%" />
        <span className="text-ay-muted">/</span>
        <ValueDeltaCell value={cell.oiPct} suffix="%" />
      </span>
      <OiBadge4 value={cell.interpretation} />
    </div>
  );
}

function Legend() {
  return (
    <div className="mt-3 flex flex-wrap gap-2 rounded border border-ay-border bg-surface-1 p-2">
      {STATES.map((s) => {
        const m = oiIntMeta(s);
        return (
          <span
            key={s}
            className="rounded border border-ay-border px-2 py-0.5 text-xs font-medium text-ay-text"
          >
            {m.abbr} = {m.label}
          </span>
        );
      })}
    </div>
  );
}

interface BanksAnalysisTableProps {
  banks: string[];
  rows: BankAnalysisRow[];
  intervalMinutes: number;
  emptyMessage?: string;
}

export function BanksAnalysisTable({
  banks,
  rows,
  intervalMinutes,
  emptyMessage = 'No bank-futures matrix yet — pick an interval with a captured session.',
}: BanksAnalysisTableProps) {
  const [page, setPage] = useState(0);
  const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const shown = useMemo(
    () => rows.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE),
    [rows, safePage],
  );
  const from = rows.length === 0 ? 0 : safePage * PAGE_SIZE + 1;
  const to = Math.min(rows.length, (safePage + 1) * PAGE_SIZE);

  return (
    <div>
      <div
        className="overflow-x-auto rounded border border-ay-border"
        tabIndex={0}
        role="region"
        aria-label="Banks Analysis OI matrix"
      >
        <table className="w-full border-collapse whitespace-nowrap text-xs">
          <thead className="bg-surface-1 text-ay-muted">
            <tr>
              <th scope="col" className="px-2 py-1 text-right font-medium">#</th>
              <th scope="col" className="px-2 py-1 text-left font-medium">Time</th>
              {banks.map((b) => (
                <th key={b} scope="col" className="px-2 py-1 text-center font-medium">
                  {b}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((r, i) => (
              <tr key={r.bucket} className="border-t border-ay-border text-ay-text">
                <td className="px-2 py-1 text-right tabular-nums text-ay-muted">
                  {safePage * PAGE_SIZE + i + 1}
                </td>
                <td className="px-2 py-1 text-left font-semibold tabular-nums">
                  {bucketWindow(r.bucket, intervalMinutes)}
                </td>
                {banks.map((b, idx) => (
                  <td key={b} className="px-2 py-1 text-center">
                    <BankCell cell={r.cells[idx] ?? null} />
                  </td>
                ))}
              </tr>
            ))}
            {shown.length === 0 && (
              <tr>
                <td colSpan={banks.length + 2} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {rows.length > 0 && (
        <div className="mt-2 flex items-center justify-between text-xs text-ay-muted">
          <span className="tabular-nums">
            {from} – {to} of {rows.length}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={safePage === 0}
              className="rounded border border-ay-border px-2 py-1 text-ay-text hover:border-accent disabled:opacity-40"
            >
              ‹ Previous
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
              disabled={safePage >= pageCount - 1}
              className="rounded border border-ay-border px-2 py-1 text-ay-text hover:border-accent disabled:opacity-40"
            >
              Next ›
            </button>
          </div>
        </div>
      )}

      <Legend />
    </div>
  );
}
