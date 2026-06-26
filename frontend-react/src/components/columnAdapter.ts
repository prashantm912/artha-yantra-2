import type { ColumnDef, SortingFn } from '@tanstack/react-table';
import { compareDecimal } from '../lib/decimal.ts';
import type { ColumnAlign, DataColumn } from './DataTable.tsx';

// Adapter (§3.2.2): maps the frozen public DataColumn<Row> shape onto a TanStack ColumnDef.
// The accessorFn drives sort/filter/facet ONLY — the visible cell stays the caller's render(row).
// Money sorts through compareDecimal (never parseFloat); nulls go last via sortUndefined.

/** Per-column data carried on ColumnDef.meta for the renderer (alignment, classes, mobile label, pin). */
export interface AyColumnMeta<Row> {
  align: ColumnAlign;
  mono: boolean;
  help?: string;
  headerClassName?: string;
  cellClassName?: (row: Row) => string;
  mobileLabel?: string;
  pin?: 'left';
  filter?: 'text' | 'select';
}

const decimalSort: SortingFn<unknown> = (a, b, id) =>
  compareDecimal(String(a.getValue(id) ?? ''), String(b.getValue(id) ?? ''));

export function adaptColumns<Row>(cols: DataColumn<Row>[]): ColumnDef<Row>[] {
  return cols.map((c) => ({
    id: c.id,
    accessorFn: c.sortValue ?? (() => null),
    header: c.header,
    cell: (info) => c.render(info.row.original),
    enableSorting: !!c.sortValue,
    enableHiding: !c.lockVisible,
    enableColumnFilter: !!c.filter,
    sortingFn:
      c.sortType === 'decimal' ? (decimalSort as SortingFn<Row>) : c.sortType === 'text' ? 'text' : 'basic',
    sortUndefined: 'last',
    meta: {
      align: c.align ?? 'right',
      mono: c.mono ?? (c.align ?? 'right') === 'right',
      help: c.help,
      headerClassName: c.headerClassName,
      cellClassName: c.cellClassName,
      mobileLabel: c.mobileLabel,
      pin: c.pin,
      filter: c.filter,
    } satisfies AyColumnMeta<Row>,
  }));
}
