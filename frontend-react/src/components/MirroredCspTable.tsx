import type { ReactNode } from 'react';
import { useMemo } from 'react';
import { compareDecimal } from '../lib/decimal.ts';
import type { LegCell, OiChainRow } from '../api/types.ts';
import { cn } from '../lib/cn.ts';

// Tier-A archetype (replication-plan): the mirrored Call | Strike | Put table. Reused by Options OI
// Analysis, Options Chain, Open&High — each supplies its own CE/PE column config (the leaky-config
// pattern: structure is shared, columns differ per page). Desktop = mirrored grid; phone = card/strike.

/** Per-row, per-side render context handed to each column's cell. */
export interface CspCellCtx {
  /** Largest leg OI across the chain (bar scaling). */
  maxOi: number;
  /** Underlying spot (decimal string) for ITM. */
  spot: string | null;
  /** True when this side's leg is in-the-money at this strike. */
  itm: boolean;
}

export interface CspColumn {
  header: string;
  cell: (leg: LegCell | null, row: OiChainRow, ctx: CspCellCtx) => ReactNode;
  align?: 'left' | 'right' | 'center';
  /** Optional per-cell class (e.g. ITM tint) computed from the side context. */
  cellClass?: (ctx: CspCellCtx) => string;
}

interface MirroredCspTableProps {
  rows: OiChainRow[];
  spot: string | null;
  ceColumns: CspColumn[];
  peColumns: CspColumn[];
  ariaLabel?: string;
  emptyMessage?: string;
}

function alignClass(a: CspColumn['align']): string {
  return a === 'center' ? 'text-center' : a === 'left' ? 'text-left' : 'text-right';
}

function legCtx(row: OiChainRow, spot: string | null, maxOi: number, side: 'ce' | 'pe'): CspCellCtx {
  const s = row.spot ?? spot;
  const itm = s != null && (side === 'ce' ? compareDecimal(row.strike, s) < 0 : compareDecimal(row.strike, s) > 0);
  return { maxOi, spot: s, itm };
}

export function MirroredCspTable({
  rows,
  spot,
  ceColumns,
  peColumns,
  ariaLabel = 'Options OI chain',
  emptyMessage = 'No chain snapshots for this selection.',
}: MirroredCspTableProps) {
  const maxOi = useMemo(
    () => rows.reduce((m, r) => Math.max(m, r.ce?.oi ?? 0, r.pe?.oi ?? 0), 1),
    [rows],
  );
  const colCount = ceColumns.length + 1 + peColumns.length;

  return (
    <>
      {/* Desktop / landscape: mirrored CE | Strike | PE grid */}
      <div
        className="hidden max-h-[62vh] overflow-auto rounded border border-ay-border md:block"
        tabIndex={0}
        role="region"
        aria-label={ariaLabel}
      >
        <table className="w-full border-collapse text-sm">
          <thead className="sticky top-0 bg-surface-1 text-ay-muted">
            <tr>
              {ceColumns.map((c) => (
                <th key={`ce-${c.header}`} scope="col" className={cn('px-2 py-1', alignClass(c.align))}>
                  {c.header}
                </th>
              ))}
              <th scope="col" className="px-2 py-1 text-center">
                Strike
              </th>
              {peColumns.map((c) => (
                <th key={`pe-${c.header}`} scope="col" className={cn('px-2 py-1', alignClass(c.align))}>
                  {c.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const ce = legCtx(row, spot, maxOi, 'ce');
              const pe = legCtx(row, spot, maxOi, 'pe');
              return (
                <tr key={row.strike} className="border-t border-ay-border text-ay-text">
                  {ceColumns.map((c) => (
                    <td
                      key={`ce-${c.header}`}
                      className={cn('px-2 py-1', alignClass(c.align), c.cellClass?.(ce))}
                    >
                      {c.cell(row.ce, row, ce)}
                    </td>
                  ))}
                  <td className="px-2 py-1 text-center font-semibold">{row.strike}</td>
                  {peColumns.map((c) => (
                    <td
                      key={`pe-${c.header}`}
                      className={cn('px-2 py-1', alignClass(c.align), c.cellClass?.(pe))}
                    >
                      {c.cell(row.pe, row, pe)}
                    </td>
                  ))}
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={colCount} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phone (portrait, ~480px): card per strike */}
      <div className="space-y-2 md:hidden">
        {rows.map((row) => {
          const ce = legCtx(row, spot, maxOi, 'ce');
          const pe = legCtx(row, spot, maxOi, 'pe');
          return (
            <div key={row.strike} className="rounded border border-ay-border bg-surface-1 p-2 text-sm">
              <div className="mb-1 font-semibold text-ay-text">Strike {row.strike}</div>
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div>
                  <div className="text-ay-muted">CE</div>
                  {ceColumns.map((c) => (
                    <div key={`ce-${c.header}`} className="text-ay-text">
                      {c.header}: {c.cell(row.ce, row, ce)}
                    </div>
                  ))}
                </div>
                <div>
                  <div className="text-ay-muted">PE</div>
                  {peColumns.map((c) => (
                    <div key={`pe-${c.header}`} className="text-ay-text">
                      {c.header}: {c.cell(row.pe, row, pe)}
                    </div>
                  ))}
                </div>
              </div>
            </div>
          );
        })}
        {rows.length === 0 && <p className="text-center text-ay-muted">{emptyMessage}</p>}
      </div>
    </>
  );
}
