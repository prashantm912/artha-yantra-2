import { useMemo } from 'react';
import { cn } from '../lib/cn.ts';
import { formatDecimal } from '../lib/decimal.ts';
import { bucketWindow, type OiAnalysisRow, type OiAnalysisSide } from '../api/oiAnalysisFold.ts';
import { OiBadge4 } from './atoms/OiBadge4.tsx';
import { ValueDeltaCell } from './atoms/ValueDeltaCell.tsx';

// The true oipulse "Options OI Analysis" (§20.7.5): per-strike intraday, BUCKETS-ON-ROWS (one row per
// interval), mirrored Call | Strike | Put. Distinct from MirroredCspTable / OptionsChainTable (both
// strike-rows). Newest interval first. Money/OI are decimal strings / longs — never parseFloat.

const num = (n: number | null) => (n != null ? n.toLocaleString('en-IN') : '—');
const dec = (v: string | null, d: number) => (v ? formatDecimal(v, d) : '—');

function SignedNum({ n }: { n: number | null }) {
  if (n == null) return <span className="text-ay-muted">—</span>;
  const tone = n === 0 ? '' : n > 0 ? 'text-bull' : 'text-bear';
  return (
    <span className={cn('tabular-nums', tone)}>
      {n > 0 ? '+' : ''}
      {n.toLocaleString('en-IN')}
    </span>
  );
}

function BreakBadge({ side }: { side: OiAnalysisSide }) {
  // breakLevel = the prior extreme the bucket broke (oipulse shows that level, not the current LTP).
  const level = side.breakLevel ? `(${formatDecimal(side.breakLevel, 2)}) ` : '';
  if (side.dhBreak) {
    return <span className="rounded bg-bull/15 px-1 text-xs text-bull">D.H.B {level}↑</span>;
  }
  if (side.dlBreak) {
    return <span className="rounded bg-bear/15 px-1 text-xs text-bear">D.L.B {level}↓</span>;
  }
  return null; // oipulse renders a blank cell (not an em-dash) when there is no break
}

interface OiAnalysisTableProps {
  rows: OiAnalysisRow[];
  strike: string | null;
  intervalMinutes: number;
  emptyMessage?: string;
}

export function OiAnalysisTable({
  rows,
  strike,
  intervalMinutes,
  emptyMessage = 'No intraday data for this strike.',
}: OiAnalysisTableProps) {
  // Newest interval first (intraday monitoring); the fold is oldest-first.
  const display = useMemo(() => [...rows].reverse(), [rows]);

  return (
    <>
      {/* Desktop / landscape: the full 16-col mirrored time-rows grid */}
      <div
        className="hidden max-h-[64vh] overflow-auto rounded border border-ay-border md:block"
        tabIndex={0}
        role="region"
        aria-label="Options OI analysis"
      >
        <table aria-label="Options OI analysis" className="w-full border-collapse whitespace-nowrap text-xs">
          <thead className="sticky top-0 z-10 bg-surface-1 text-ay-muted">
            <tr>
              <th colSpan={8} scope="colgroup" className="px-2 py-1 text-center text-bear">CALL</th>
              <th scope="col" className="px-2 py-1 text-center">Strike</th>
              <th colSpan={7} scope="colgroup" className="px-2 py-1 text-center text-bull">PUT</th>
            </tr>
            <tr>
              <th scope="col" className="px-2 py-1 text-left font-medium">Time</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Call OI</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Total OI Chng</th>
              <th scope="col" className="px-2 py-1 text-center font-medium">D.H/L</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Call LTP</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">LTP Chng</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Chng. OI</th>
              <th scope="col" className="px-2 py-1 text-center font-medium">OI Int.</th>
              <th scope="col" className="px-2 py-1 text-center font-medium">Strike</th>
              <th scope="col" className="px-2 py-1 text-center font-medium">OI Int.</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Chng. OI</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">LTP Chng</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Put LTP</th>
              <th scope="col" className="px-2 py-1 text-center font-medium">D.H/L</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Total OI Chng</th>
              <th scope="col" className="px-2 py-1 text-right font-medium">Put OI</th>
            </tr>
          </thead>
          <tbody>
            {display.map((r) => (
              <tr key={r.bucket} className="border-t border-ay-border text-ay-text">
                <td className="px-2 py-1 text-left font-semibold tabular-nums">{bucketWindow(r.bucket, intervalMinutes)}</td>
                <td className="px-2 py-1 text-right tabular-nums">{num(r.ce.oi)}</td>
                <td className="px-2 py-1 text-right tabular-nums">{num(r.ce.cumOiChange)}</td>
                <td className="px-2 py-1 text-center"><BreakBadge side={r.ce} /></td>
                <td className="px-2 py-1 text-right tabular-nums">{dec(r.ce.ltp, 2)}</td>
                <td className="px-2 py-1 text-right"><ValueDeltaCell value={r.ce.ltpChange} /></td>
                <td className="px-2 py-1 text-right"><SignedNum n={r.ce.oiChange} /></td>
                <td className="px-2 py-1 text-center"><OiBadge4 value={r.ce.interpretation} full /></td>
                <td className="px-2 py-1 text-center font-semibold tabular-nums">{strike ?? '—'}</td>
                <td className="px-2 py-1 text-center"><OiBadge4 value={r.pe.interpretation} full /></td>
                <td className="px-2 py-1 text-right"><SignedNum n={r.pe.oiChange} /></td>
                <td className="px-2 py-1 text-right"><ValueDeltaCell value={r.pe.ltpChange} /></td>
                <td className="px-2 py-1 text-right tabular-nums">{dec(r.pe.ltp, 2)}</td>
                <td className="px-2 py-1 text-center"><BreakBadge side={r.pe} /></td>
                <td className="px-2 py-1 text-right tabular-nums">{num(r.pe.cumOiChange)}</td>
                <td className="px-2 py-1 text-right tabular-nums">{num(r.pe.oi)}</td>
              </tr>
            ))}
            {display.length === 0 && (
              <tr>
                <td colSpan={16} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phone (portrait): card per interval, CALL/PUT split */}
      <div className="space-y-2 md:hidden">
        {display.map((r) => (
          <div key={r.bucket} className="rounded border border-ay-border bg-surface-1 p-2 text-xs">
            <div className="mb-1 flex items-center justify-between font-semibold text-ay-text">
              <span>{bucketWindow(r.bucket, intervalMinutes)}</span>
              <span className="text-ay-muted">Strike {strike ?? '—'}</span>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <div className="text-bear">CALL</div>
                <div>OI: {num(r.ce.oi)}</div>
                <div>Chng. OI: <SignedNum n={r.ce.oiChange} /></div>
                <div>LTP: {dec(r.ce.ltp, 2)} (<ValueDeltaCell value={r.ce.ltpChange} />)</div>
                <div>OI Int: <OiBadge4 value={r.ce.interpretation} full /></div>
              </div>
              <div>
                <div className="text-bull">PUT</div>
                <div>OI: {num(r.pe.oi)}</div>
                <div>Chng. OI: <SignedNum n={r.pe.oiChange} /></div>
                <div>LTP: {dec(r.pe.ltp, 2)} (<ValueDeltaCell value={r.pe.ltpChange} />)</div>
                <div>OI Int: <OiBadge4 value={r.pe.interpretation} full /></div>
              </div>
            </div>
          </div>
        ))}
        {display.length === 0 && <p className="text-center text-ay-muted">{emptyMessage}</p>}
      </div>
    </>
  );
}
