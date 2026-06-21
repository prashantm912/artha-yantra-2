import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { cn } from '../lib/cn.ts';
import { compareDecimal } from '../lib/decimal.ts';

// The generic Wave-2 table composite (master plan §20): one config-driven, client-sorted, paginated
// table that every depth page's tabular archetype consumes (Trending OI / Big OI / Futures EOD / FII
// Capital Market / Participant-wise OI). Hand-rolled — no table lib — matching the repo's existing
// bespoke tables (OiAnalysisTable / SpurtQuadrant): a sticky-header desktop <table> + a md:hidden
// per-row card list (S24-Ultra ~480px baseline). Money is sorted via compareDecimal — never parseFloat.

export type ColumnAlign = 'left' | 'right' | 'center';

export interface DataColumn<Row> {
  id: string;
  header: string;
  align?: ColumnAlign;
  /** Returns the sort key; a column is sortable iff this is defined. null sorts last either way. */
  sortValue?: (row: Row) => number | string | null;
  /** How {@link sortValue} compares: numeric, exact-decimal-string, or text. Default 'number'. */
  sortType?: 'number' | 'decimal' | 'text';
  /** The cell body. Closures may capture page-level memo (e.g. a DataBar max) directly. */
  render: (row: Row) => ReactNode;
  /** Extra per-cell classes (max-cell tint, ITM tint, sign tone). */
  cellClassName?: (row: Row) => string;
  headerClassName?: string;
  /** Card-mode label; omit to hide this column on phones. */
  mobileLabel?: string;
}

interface DataTableProps<Row> {
  columns: DataColumn<Row>[];
  rows: Row[];
  rowKey: (row: Row) => string;
  /** 0 (default) = no pagination (scroll container). */
  pageSize?: number;
  initialSort?: { id: string; dir: 'asc' | 'desc' };
  emptyMessage?: string;
  ariaLabel: string;
  /** Optional per-row class on the desktop <tr> + mobile card (e.g. bold a strong row). */
  rowClassName?: (row: Row) => string;
}

const ALIGN: Record<ColumnAlign, string> = {
  left: 'text-left',
  right: 'text-right',
  center: 'text-center',
};

function compareValues(
  a: number | string | null,
  b: number | string | null,
  type: 'number' | 'decimal' | 'text',
): number {
  // Nulls sort last in ascending order (and the dir flip pushes them first in descending — oipulse
  // shows blanks at the bottom of the active sort, which the caller can refine if needed).
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  if (type === 'decimal') return compareDecimal(String(a), String(b));
  if (type === 'text') return String(a).localeCompare(String(b));
  return Number(a) - Number(b);
}

export function DataTable<Row>({
  columns,
  rows,
  rowKey,
  pageSize = 0,
  initialSort,
  emptyMessage = 'No data.',
  ariaLabel,
  rowClassName,
}: DataTableProps<Row>) {
  const [sort, setSort] = useState<{ id: string; dir: 'asc' | 'desc' } | null>(initialSort ?? null);
  const [page, setPage] = useState(0);

  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.id === sort.id);
    if (!col?.sortValue) return rows;
    const accessor = col.sortValue;
    const type = col.sortType ?? 'number';
    const factor = sort.dir === 'asc' ? 1 : -1;
    // Stable sort: decorate with the original index so equal keys keep input order.
    return [...rows]
      .map((row, i) => ({ row, i }))
      .sort((x, y) => {
        const c = compareValues(accessor(x.row), accessor(y.row), type);
        return c !== 0 ? c * factor : x.i - y.i;
      })
      .map((d) => d.row);
  }, [rows, columns, sort]);

  // Reset to the first page whenever the row set or the sort changes.
  useEffect(() => setPage(0), [sorted.length, sort]);

  const paginated = pageSize > 0;
  const pages = paginated ? Math.max(1, Math.ceil(sorted.length / pageSize)) : 1;
  const clamped = Math.min(page, pages - 1);
  const start = paginated ? clamped * pageSize : 0;
  const slice = paginated ? sorted.slice(start, start + pageSize) : sorted;

  function toggleSort(id: string) {
    setSort((s) =>
      s?.id === id ? { id, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { id, dir: 'desc' },
    );
  }

  const mobileCols = columns.filter((c) => c.mobileLabel);

  return (
    <div>
      {/* Desktop / landscape: sticky-header sortable table */}
      <div
        className="hidden max-h-[68vh] overflow-auto rounded border border-ay-border md:block"
        tabIndex={0}
        role="region"
        aria-label={ariaLabel}
      >
        <table className="w-full border-collapse whitespace-nowrap text-xs">
          <thead className="sticky top-0 z-10 bg-surface-1 text-ay-muted">
            <tr>
              {columns.map((c) => {
                const active = sort?.id === c.id;
                const ariaSort = active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none';
                return (
                  <th
                    key={c.id}
                    scope="col"
                    aria-sort={c.sortValue ? ariaSort : undefined}
                    className={cn('px-2 py-1 font-medium', ALIGN[c.align ?? 'right'], c.headerClassName)}
                  >
                    {c.sortValue ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(c.id)}
                        className="inline-flex items-center gap-0.5 hover:text-accent"
                      >
                        {c.header}
                        <span aria-hidden="true" className="text-[0.6rem]">
                          {active ? (sort.dir === 'asc' ? '▲' : '▼') : '⇅'}
                        </span>
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {slice.map((row) => (
              <tr
                key={rowKey(row)}
                className={cn('border-t border-ay-border text-ay-text', rowClassName?.(row))}
              >
                {columns.map((c) => (
                  <td
                    key={c.id}
                    className={cn('px-2 py-1 tabular-nums', ALIGN[c.align ?? 'right'], c.cellClassName?.(row))}
                  >
                    {c.render(row)}
                  </td>
                ))}
              </tr>
            ))}
            {sorted.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phone (portrait): one card per row, label: value per mobile-visible column */}
      <div className="space-y-2 md:hidden">
        {slice.map((row) => (
          <div
            key={rowKey(row)}
            className={cn('rounded border border-ay-border bg-surface-1 p-2 text-xs', rowClassName?.(row))}
          >
            <dl className="grid grid-cols-2 gap-x-2 gap-y-0.5">
              {mobileCols.map((c) => (
                <div key={c.id} className="flex justify-between gap-2">
                  <dt className="text-ay-muted">{c.mobileLabel}</dt>
                  <dd className={cn('tabular-nums', c.cellClassName?.(row))}>{c.render(row)}</dd>
                </div>
              ))}
            </dl>
          </div>
        ))}
        {sorted.length === 0 && <p className="text-center text-ay-muted">{emptyMessage}</p>}
      </div>

      {paginated && sorted.length > pageSize && (
        <div className="mt-1.5 flex items-center justify-between px-1 text-xs text-ay-muted">
          <span className="tabular-nums">
            {start + 1}–{Math.min(start + pageSize, sorted.length)} of {sorted.length}
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
