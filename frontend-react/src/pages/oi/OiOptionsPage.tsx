import { useMemo } from 'react';
import { compareDecimal, formatDecimal } from '../../lib/decimal.ts';
import { chainSpot, foldStrikes, maxOptionOi } from '../../api/oiFold.ts';
import {
  useActiveStrikes,
  useOiAnalysis,
  useOiStats,
  useOptionsSpurt,
} from '../../api/oiAnalytics.ts';
import type { OiChainRow } from '../../api/types.ts';
import { FilterBar } from '../../components/FilterBar.tsx';
import { OiBadge4 } from '../../components/atoms/OiBadge4.tsx';
import { DataBar } from '../../components/atoms/DataBar.tsx';
import { cn } from '../../lib/cn.ts';

// Options OI Analysis — the PR-F anchor page (master plan §20). Mirrored CE/PE strike grid with
// in-cell OI bars + a PCR/max-pain/sentiment header, driven by the shared FilterBar selection.
// All money/IV are decimal strings (never parseFloat). Desktop = mirrored table; phone = card-per-strike.

const dec = (v: string | null | undefined, n: number) => (v ? formatDecimal(v, n) : '—');
const oiFmt = (v: number | null | undefined) => (v != null ? v.toLocaleString('en-IN') : '—');
const signedOi = (v: number | null | undefined) =>
  v == null ? '—' : v > 0 ? '+' + v.toLocaleString('en-IN') : v.toLocaleString('en-IN');
const toneClass = (v: number | null | undefined) =>
  v == null || v === 0 ? '' : v > 0 ? 'text-bull' : 'text-bear';

function itm(row: OiChainRow, spot: string | null, side: 'ce' | 'pe'): boolean {
  const s = row.spot ?? spot;
  if (s == null) return false;
  const cmp = compareDecimal(row.strike, s);
  return side === 'ce' ? cmp < 0 : cmp > 0;
}

export function OiOptionsPage() {
  const stats = useOiStats();
  const active = useActiveStrikes();
  const strikesQ = useOiAnalysis();
  const spurt = useOptionsSpurt();

  const points = useMemo(() => strikesQ.data ?? [], [strikesQ.data]);
  const rows = useMemo(() => foldStrikes(points), [points]);
  const maxOi = useMemo(() => maxOptionOi(points), [points]);
  const spot = useMemo(() => chainSpot(points), [points]);
  const bias = spurt.data?.summary?.interpretation ?? null;
  const s = stats.data;

  return (
    <div>
      <h1 className="ay-sr-only">Options OI analysis</h1>
      <FilterBar showName showExpiry />

      <p className="mb-2 flex items-center gap-2 text-sm text-ay-muted" aria-live="polite">
        OI bias <OiBadge4 value={bias} />
      </p>

      {s ? (
        <p className="mb-3 text-sm text-ay-muted tabular-nums" aria-live="polite">
          PCR {dec(s.pcr, 4)} · Max pain {dec(s.maxPain, 2)} · CE OI {oiFmt(s.ceOi)} · PE OI{' '}
          {oiFmt(s.peOi)}
          {active.data && <> · Sentiment {dec(active.data.sentimentPct, 2)}%</>} · {rows.length}{' '}
          strike{rows.length === 1 ? '' : 's'}
        </p>
      ) : (
        <p className="mb-3 text-sm text-ay-muted">
          No OI stats — pick an underlying + expiry with captured snapshots.
        </p>
      )}

      {/* Desktop / landscape: mirrored CE | Strike | PE grid */}
      <div className="hidden max-h-[62vh] overflow-auto rounded border border-ay-border md:block">
        <table className="w-full border-collapse text-sm">
          <thead className="sticky top-0 bg-surface-1 text-ay-muted">
            <tr>
              <th className="px-2 py-1 text-right">CE OI</th>
              <th className="px-2 py-1 text-right">CE ΔOI</th>
              <th className="px-2 py-1 text-right">CE IV</th>
              <th className="px-2 py-1 text-right">CE LTP</th>
              <th className="px-2 py-1 text-center">Strike</th>
              <th className="px-2 py-1 text-right">PE LTP</th>
              <th className="px-2 py-1 text-right">PE IV</th>
              <th className="px-2 py-1 text-right">PE ΔOI</th>
              <th className="px-2 py-1 text-right">PE OI</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.strike} className="border-t border-ay-border text-ay-text">
                <td className="px-2 py-1">
                  <DataBar value={row.ce?.oi ?? 0} max={maxOi} label={oiFmt(row.ce?.oi)} />
                </td>
                <td className={cn('px-2 py-1 text-right tabular-nums', toneClass(row.ce?.oiChange))}>
                  {signedOi(row.ce?.oiChange)}
                </td>
                <td className="px-2 py-1 text-right tabular-nums">{dec(row.ce?.iv, 4)}</td>
                <td
                  className={cn(
                    'px-2 py-1 text-right tabular-nums',
                    itm(row, spot, 'ce') && 'bg-accent/10',
                  )}
                >
                  {dec(row.ce?.ltp, 2)}
                  {itm(row, spot, 'ce') && <span className="ay-sr-only"> in the money</span>}
                </td>
                <td className="px-2 py-1 text-center font-semibold">{row.strike}</td>
                <td
                  className={cn(
                    'px-2 py-1 text-right tabular-nums',
                    itm(row, spot, 'pe') && 'bg-accent/10',
                  )}
                >
                  {dec(row.pe?.ltp, 2)}
                  {itm(row, spot, 'pe') && <span className="ay-sr-only"> in the money</span>}
                </td>
                <td className="px-2 py-1 text-right tabular-nums">{dec(row.pe?.iv, 4)}</td>
                <td className={cn('px-2 py-1 text-right tabular-nums', toneClass(row.pe?.oiChange))}>
                  {signedOi(row.pe?.oiChange)}
                </td>
                <td className="px-2 py-1">
                  <DataBar value={row.pe?.oi ?? 0} max={maxOi} label={oiFmt(row.pe?.oi)} />
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={9} className="px-2 py-4 text-center text-ay-muted">
                  No chain snapshots for this selection.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phone (portrait, ~480px): card per strike */}
      <div className="space-y-2 md:hidden">
        {rows.map((row) => (
          <div key={row.strike} className="rounded border border-ay-border bg-surface-1 p-2 text-sm">
            <div className="mb-1 flex items-center justify-between">
              <span className="font-semibold text-ay-text">Strike {row.strike}</span>
            </div>
            <div className="grid grid-cols-2 gap-2 text-xs tabular-nums">
              <div>
                <div className="text-ay-muted">CE</div>
                <div className="text-ay-text">
                  OI {oiFmt(row.ce?.oi)} · LTP {dec(row.ce?.ltp, 2)}
                </div>
                <div className={toneClass(row.ce?.oiChange)}>ΔOI {signedOi(row.ce?.oiChange)}</div>
              </div>
              <div>
                <div className="text-ay-muted">PE</div>
                <div className="text-ay-text">
                  OI {oiFmt(row.pe?.oi)} · LTP {dec(row.pe?.ltp, 2)}
                </div>
                <div className={toneClass(row.pe?.oiChange)}>ΔOI {signedOi(row.pe?.oiChange)}</div>
              </div>
            </div>
          </div>
        ))}
        {rows.length === 0 && (
          <p className="text-center text-ay-muted">No chain snapshots for this selection.</p>
        )}
      </div>
    </div>
  );
}
