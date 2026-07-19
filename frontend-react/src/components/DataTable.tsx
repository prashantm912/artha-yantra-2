import {
  Fragment,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type HTMLAttributes,
  type ReactNode,
} from 'react';
import {
  flexRender,
  getCoreRowModel,
  getFacetedRowModel,
  getFacetedUniqueValues,
  getFilteredRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type Column,
  type ColumnPinningState,
  type OnChangeFn,
  type SortingState,
  type VisibilityState,
} from '@tanstack/react-table';
import { ChevronDown, ChevronRight, ChevronUp, ChevronsUpDown, Columns3 } from 'lucide-react';
import { cn } from '../lib/cn.ts';
import { InfoTip } from './atoms/InfoTip.tsx';
import { adaptColumns, type AyColumnMeta } from './columnAdapter.ts';
import { useCenterRowInScroll } from '../lib/scrollToCenter.ts';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from './ui/dropdown-menu.tsx';

// The generic Wave-2 table composite (master plan §20 / revamp §3.2): one config-driven, client-sorted,
// paginated table that every depth page's tabular archetype consumes. TanStack Table v8 (headless) drives
// sort/filter/paginate; the markup is hand-owned so it stays a real <table><thead><th><tbody><td> — never
// role="grid" — keeping getByRole('table'|'columnheader') green across ~20 pages. A sticky-header desktop
// table + a md:hidden per-row card list (S24-Ultra ~480px baseline). Money is sorted via compareDecimal
// (in columnAdapter) — never parseFloat. Every new feature is behind an OPTIONAL prop, so omitting them
// reproduces today's exact render.

export type ColumnAlign = 'left' | 'right' | 'center';

export interface DataColumn<Row> {
  id: string;
  header: string;
  /** Optional plain-language explanation of this field (#16) — rendered as a focusable ⓘ tooltip
   *  beside the header. Omit to render the header exactly as today. */
  help?: string;
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
  /** Sticky-left pinned column (desktop only; e.g. the STRIKE rail). */
  pin?: 'left';
  /** Faceted/text column filter (toolbar control). */
  filter?: 'text' | 'select';
  /** Force monospace numerics; defaults true for right-aligned columns. */
  mono?: boolean;
  /** Hidden by default (toggled back on via the column-visibility menu). */
  defaultHidden?: boolean;
  /** Cannot be hidden via the column-visibility menu. */
  lockVisible?: boolean;
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
  /** Show the toolbar column-visibility menu. */
  enableColumnVisibility?: boolean;
  /** Show the comfortable/compact density toggle. */
  enableDensityToggle?: boolean;
  /** Density at first render. Default 'comfortable' (today's px-2 py-1 spacing). */
  initialDensity?: 'comfortable' | 'compact';
  /** Shift-click multi-sort (default true). */
  enableMultiSort?: boolean;
  /** Zebra striping on body rows (default true). */
  zebra?: boolean;
  /** Opt-in virtualization above this row count (unimplemented stub — no-op). */
  virtualizeAfter?: number;
  /** localStorage key for persisting density/visibility (reserved). */
  persistKey?: string;
  /** rowKey of the row to centre in the scroll viewport on load / when it changes (e.g. the ATM
   *  strike). Desktop scroll container only; no-op when unset, paginated off-page, or not found. */
  scrollToRowKey?: string;
  /** Drive sorting server-side: skip the client sort, control the sort state from {@link sortState},
   *  and emit header-sort changes via {@link onSortChange} so the sort spans EVERY page (the Jobs page),
   *  not just the loaded one. Single-column; today's behaviour is unchanged unless set. */
  manualSorting?: boolean;
  sortState?: { id: string; dir: 'asc' | 'desc' } | null;
  onSortChange?: (sort: { id: string; dir: 'asc' | 'desc' } | null) => void;
  /** Make each row clickable as a MOUSE-ONLY convenience — the desktop <tr> and the mobile card both
   *  get an onClick + cursor-pointer only, NO role/tabIndex/keyboard (audit M23 / #596). A
   *  role="button" on a <tr> strips its cells of their required `row` parent → axe
   *  `aria-required-parent` + AT sees a table with zero data rows; and because the mobile card
   *  renders the same cells, a card-as-button would wrap any in-cell control as `nested-interactive`.
   *  So keyboard + AT activation MUST come from a real in-cell <button>/<a> the adopter renders and
   *  stopPropagations (the M23 pattern — see SignalsPage / BacktestResultsPage); it renders into both
   *  the desktop cell and the mobile card, so it is the keyboard path on both. Omit to keep rows
   *  plain, non-interactive (today's exact render — no role, no tabIndex, no pointer). */
  onRowClick?: (row: Row, index: number) => void;
  /** Override the desktop scroll container's max-height cap with any CSS length (e.g. '24rem',
   *  '65vh'). Omit to keep today's hardcoded max-h-[68vh]. */
  maxHeight?: string;
  /** Make every row expandable into an inline detail panel. When provided, a leading expander column
   *  appears whose control is a REAL in-cell `<button type="button">` (aria-expanded + aria-controls +
   *  an Expand/Collapse aria-label) — NEVER a role="button" on the <tr> (audit M23 / #596: that strips
   *  the cells of their required `row` parent → axe aria-required-parent + AT sees zero data rows). The
   *  button is the keyboard/AT path; a row/card mouse onClick toggles too as a convenience (only when
   *  {@link onRowClick} is unset). When a row is open, a detail <tr id=…> spanning EVERY column
   *  (expander included) renders immediately after it with {@link renderExpanded}(row); the mobile card
   *  gets the same button + the panel appended. Expansion state is internal (uncontrolled), keyed by
   *  {@link rowKey}. Omit to keep today's exact render — no expander column, no detail rows, no state. */
  renderExpanded?: (row: Row) => ReactNode;
}

const ALIGN: Record<ColumnAlign, string> = {
  left: 'text-left',
  right: 'text-right',
  center: 'text-center',
};

/** Sticky-left position for a pinned column (header sticks at z-20, body at z-11). */
function pinnedStyle<Row>(col: Column<Row, unknown>): CSSProperties | undefined {
  if (col.getIsPinned() !== 'left') return undefined;
  return { position: 'sticky', left: col.getStart('left'), zIndex: 11 };
}

const PINNED_CLASS = 'bg-surface-1 shadow-[inset_-1px_0_0_var(--ay-border)]';

export function DataTable<Row>({
  columns,
  rows,
  rowKey,
  pageSize = 0,
  initialSort,
  emptyMessage = 'No data.',
  ariaLabel,
  rowClassName,
  enableColumnVisibility = false,
  enableDensityToggle = false,
  initialDensity = 'comfortable',
  enableMultiSort = true,
  zebra = true,
  scrollToRowKey,
  manualSorting = false,
  sortState,
  onSortChange,
  onRowClick,
  maxHeight,
  renderExpanded,
  // virtualizeAfter (§3.2.5) + persistKey (§3.2.3) are accepted but intentionally unimplemented
  // no-ops for this phase — left off the destructure so omitting them yields today's exact render.
}: DataTableProps<Row>) {
  const tableColumns = useMemo(() => adaptColumns(columns), [columns]);

  const [internalSorting, setInternalSorting] = useState<SortingState>(
    initialSort ? [{ id: initialSort.id, desc: initialSort.dir === 'desc' }] : [],
  );
  // Controlled (server-side) sort when manualSorting is set; otherwise the table owns the state.
  const sorting: SortingState = manualSorting
    ? sortState
      ? [{ id: sortState.id, desc: sortState.dir === 'desc' }]
      : []
    : internalSorting;
  const handleSortingChange: OnChangeFn<SortingState> = (updater) => {
    if (manualSorting) {
      const next = typeof updater === 'function' ? updater(sorting) : updater;
      const s = next[0];
      onSortChange?.(s ? { id: s.id, dir: s.desc ? 'desc' : 'asc' } : null);
    } else {
      setInternalSorting(updater);
    }
  };
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>(() =>
    Object.fromEntries(columns.filter((c) => c.defaultHidden).map((c) => [c.id, false])),
  );
  const columnPinning = useMemo<ColumnPinningState>(
    () => ({ left: columns.filter((c) => c.pin === 'left').map((c) => c.id), right: [] }),
    [columns],
  );
  const hasFilter = columns.some((c) => c.filter);
  const paginated = pageSize > 0;

  // Optional ATM-strike centring: land the keyed row in the middle of the scroll viewport.
  const scrollRef = useRef<HTMLDivElement>(null);
  const centerRef = useRef<HTMLTableRowElement>(null);
  useCenterRowInScroll(scrollRef, centerRef, scrollToRowKey);

  const table = useReactTable<Row>({
    data: rows,
    columns: tableColumns,
    state: { sorting, columnVisibility, columnPinning },
    onSortingChange: handleSortingChange,
    onColumnVisibilityChange: setColumnVisibility,
    manualSorting,
    getRowId: (row) => rowKey(row),
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    ...(hasFilter
      ? {
          getFilteredRowModel: getFilteredRowModel(),
          getFacetedRowModel: getFacetedRowModel(),
          getFacetedUniqueValues: getFacetedUniqueValues(),
        }
      : {}),
    ...(paginated ? { getPaginationRowModel: getPaginationRowModel() } : {}),
    sortDescFirst: true, // first click = descending, second = ascending (matches today's render)
    enableSortingRemoval: false, // never an "off" state — keeps desc↔asc cycle
    enableMultiSort,
    maxMultiSortColCount: 3,
    isMultiSortEvent: (e) => (e as { shiftKey?: boolean }).shiftKey === true,
    autoResetPageIndex: true,
    initialState: paginated ? { pagination: { pageIndex: 0, pageSize } } : undefined,
  });

  // Reset to the first page whenever the sort changes (TanStack auto-resets on data/filter changes).
  useEffect(() => {
    if (paginated) table.setPageIndex(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sorting]);

  const [density, setDensity] = useState<'comfortable' | 'compact'>(initialDensity);
  const cellPad = density === 'compact' ? 'px-2 py-0.5' : 'px-2 py-1';

  const bodyRows = table.getRowModel().rows;
  const visibleLeafCount = table.getVisibleLeafColumns().length;
  const total = table.getFilteredRowModel().rows.length;
  const empty = bodyRows.length === 0;

  // Optional expandable rows (opt-in via renderExpanded). Internal, uncontrolled state keyed by rowKey.
  // The leading expander column adds one to the column count, so the detail cell — and the empty-state
  // cell — must span visibleLeafCount + 1 to reach full width. Omitting renderExpanded leaves
  // `expandable` false, so the count, the state, and the render collapse to today's exact output.
  const expandable = renderExpanded != null;
  const totalColumnCount = visibleLeafCount + (expandable ? 1 : 0);
  const instanceId = useId();
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(() => new Set());
  const toggleExpanded = (key: string) =>
    setExpandedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const mobileLeafCols = table
    .getVisibleLeafColumns()
    .filter((c) => (c.columnDef.meta as AyColumnMeta<Row> | undefined)?.mobileLabel);

  // Pagination range text (1–2 of N) computed from pagination state to byte-match today's output.
  const pageIndex = table.getState().pagination.pageIndex;
  const rangeStart = paginated ? pageIndex * pageSize + 1 : 1;
  const rangeEnd = paginated ? Math.min(rangeStart + bodyRows.length - 1, total) : total;

  const showToolbar = enableColumnVisibility || enableDensityToggle || hasFilter;

  // Row-click affordance (opt-in) — a MOUSE-ONLY convenience on BOTH the desktop <tr> and the mobile
  // card. It is deliberately NOT turned into a keyboard/AT button on either surface (audit M23 / #596):
  //  • Desktop: a role="button" on a <tr> overrides its implicit `row` role, stripping every <td> of
  //    its required `row` parent → axe `aria-required-parent`, and a screen-reader in table mode sees
  //    a table with ZERO data rows.
  //  • Mobile: the card renders the SAME cells as the row, so a card-as-button would wrap whatever
  //    in-cell control the adopter provides for keyboard access → axe `nested-interactive`.
  // So keyboard + AT activation MUST come from a real in-cell <button>/<a> the adopter renders and
  // stopPropagations (the M23 pattern — SignalsPage / BacktestResultsPage); it renders into BOTH the
  // desktop cell and the mobile card, so it is the keyboard path on both. The row/card onClick stays
  // a pure mouse convenience. Unset onRowClick → {}, leaving rows plain, non-interactive (today's
  // exact render — no role, no tabIndex, no pointer).
  // A row/card gets a MOUSE-ONLY expand-toggle when it's expandable and the adopter hasn't claimed the
  // click for its own onRowClick — this preserves the "click anywhere on the row to expand" convenience
  // the hand-rolled adopters had. The in-cell expander <button> stays the required keyboard/AT path
  // either way (and stopPropagations so it never double-fires against this row handler).
  const rowMouseToggle = expandable && onRowClick == null;
  const rowInteractive = onRowClick != null || rowMouseToggle;
  const rowClickProps = (row: Row, index: number, key: string): HTMLAttributes<HTMLElement> => {
    if (onRowClick) return { onClick: () => onRowClick(row, index) };
    if (rowMouseToggle) return { onClick: () => toggleExpanded(key) };
    return {};
  };

  return (
    <div>
      {showToolbar && (
        <div className="mb-1.5 flex items-center justify-end gap-1.5">
          {enableDensityToggle && (
            <div className="flex gap-1 text-xs">
              <button
                type="button"
                aria-pressed={density === 'comfortable'}
                onClick={() => setDensity('comfortable')}
                className="rounded border border-ay-border px-2 py-0.5 hover:border-accent aria-pressed:border-accent aria-pressed:text-accent"
              >
                Comfortable
              </button>
              <button
                type="button"
                aria-pressed={density === 'compact'}
                onClick={() => setDensity('compact')}
                className="rounded border border-ay-border px-2 py-0.5 hover:border-accent aria-pressed:border-accent aria-pressed:text-accent"
              >
                Compact
              </button>
            </div>
          )}
          {enableColumnVisibility && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  aria-label="Choose columns"
                  className="inline-flex items-center rounded border border-ay-border px-1.5 py-0.5 text-ay-muted hover:border-accent hover:text-accent"
                >
                  <Columns3 aria-hidden="true" className="size-4" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>Columns</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {table
                  .getAllLeafColumns()
                  .filter((c) => c.getCanHide())
                  .map((c) => (
                    <DropdownMenuCheckboxItem
                      key={c.id}
                      checked={c.getIsVisible()}
                      onCheckedChange={(v) => c.toggleVisibility(!!v)}
                      onSelect={(e) => e.preventDefault()}
                    >
                      {String((c.columnDef.meta as AyColumnMeta<Row> | undefined)?.mobileLabel ?? c.id)}
                    </DropdownMenuCheckboxItem>
                  ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      )}

      {/* Desktop / landscape: sticky-header sortable table */}
      <div
        ref={scrollRef}
        className={cn(
          'ay-table-scroll hidden overflow-auto rounded border border-ay-border md:block',
          // Keep the hardcoded cap (today's exact render) unless an explicit maxHeight overrides it.
          maxHeight == null && 'max-h-[68vh]',
        )}
        style={maxHeight != null ? { maxHeight } : undefined}
        tabIndex={0}
        role="region"
        aria-label={ariaLabel}
      >
        <table aria-label={ariaLabel} className="w-full border-collapse whitespace-nowrap text-xs">
          <thead className="sticky top-0 z-10 bg-surface-1 text-ay-muted shadow-[inset_0_-1px_0_var(--ay-border)]">
            {table.getHeaderGroups().map((hg) => (
              <tr key={hg.id}>
                {expandable && (
                  <th scope="col" className={cn(cellPad, 'w-8')}>
                    <span className="ay-sr-only">Expand</span>
                  </th>
                )}
                {hg.headers.map((h) => {
                  const col = h.column;
                  const meta = col.columnDef.meta as AyColumnMeta<Row>;
                  const sortable = col.getCanSort();
                  const sortDir = col.getIsSorted();
                  const ariaSort = sortable
                    ? sortDir === 'asc'
                      ? 'ascending'
                      : sortDir === 'desc'
                        ? 'descending'
                        : 'none'
                    : undefined;
                  const pinned = col.getIsPinned() === 'left';
                  const sortIndex = col.getSortIndex();
                  const Chevron =
                    sortDir === 'asc' ? ChevronUp : sortDir === 'desc' ? ChevronDown : ChevronsUpDown;
                  // The header label is a plain string (DataColumn.header) → use it to name the ⓘ
                  // trigger for screen readers ("Open Interest — help").
                  const headerText =
                    typeof col.columnDef.header === 'string' ? col.columnDef.header : undefined;
                  return (
                    <th
                      key={h.id}
                      scope="col"
                      aria-sort={ariaSort}
                      style={pinnedStyle(col)}
                      className={cn(
                        cellPad,
                        'font-medium',
                        ALIGN[meta.align],
                        pinned && cn(PINNED_CLASS, 'z-20'),
                        meta.headerClassName,
                      )}
                    >
                      {/* header label (+ optional sort affordance) and an optional ⓘ help tooltip
                          ride together in one inline-flex group so the icon never triggers a sort. */}
                      <span className="inline-flex items-center gap-1">
                        {sortable ? (
                          <button
                            type="button"
                            onClick={col.getToggleSortingHandler()}
                            className="inline-flex items-center gap-0.5 rounded-sm hover:text-accent focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent"
                          >
                            {flexRender(col.columnDef.header, h.getContext())}
                            <Chevron aria-hidden="true" className="size-3 text-ay-muted" />
                            {sorting.length > 1 && sortIndex >= 0 && (
                              <span aria-hidden="true" className="text-dense text-ay-muted">
                                {sortIndex + 1}
                              </span>
                            )}
                          </button>
                        ) : (
                          flexRender(col.columnDef.header, h.getContext())
                        )}
                        {meta.help && <InfoTip text={meta.help} label={headerText} />}
                      </span>
                    </th>
                  );
                })}
              </tr>
            ))}
          </thead>
          <tbody>
            {bodyRows.map((r, i) => {
              const isOpen = expandable && expandedKeys.has(r.id);
              const detailId = expandable ? `${instanceId}-${r.id}-detail` : undefined;
              return (
                <Fragment key={r.id}>
                  <tr
                    ref={scrollToRowKey != null && r.id === scrollToRowKey ? centerRef : null}
                    {...rowClickProps(r.original, i, r.id)}
                    className={cn(
                      'border-t border-ay-border text-ay-text transition-colors',
                      zebra && (i % 2 === 1 ? 'bg-surface-1/40' : 'bg-transparent'),
                      'hover:bg-surface-2/60',
                      rowInteractive && 'cursor-pointer',
                      rowClassName?.(r.original),
                    )}
                  >
                    {expandable && (
                      <td className={cn(cellPad, 'w-8 text-center')}>
                        <button
                          type="button"
                          aria-expanded={isOpen}
                          aria-controls={detailId}
                          aria-label={isOpen ? 'Collapse details' : 'Expand details'}
                          onClick={(e) => {
                            e.stopPropagation();
                            toggleExpanded(r.id);
                          }}
                          className="inline-flex items-center justify-center rounded-sm text-ay-muted hover:text-accent focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent"
                        >
                          <ChevronRight
                            aria-hidden="true"
                            className={cn('size-4 transition-transform', isOpen && 'rotate-90')}
                          />
                        </button>
                      </td>
                    )}
                    {r.getVisibleCells().map((cell) => {
                      const meta = cell.column.columnDef.meta as AyColumnMeta<Row>;
                      const pinned = cell.column.getIsPinned() === 'left';
                      return (
                        <td
                          key={cell.id}
                          style={pinnedStyle(cell.column)}
                          className={cn(
                            cellPad,
                            meta.mono && 'tabular-nums',
                            ALIGN[meta.align],
                            pinned && PINNED_CLASS,
                            meta.cellClassName?.(r.original),
                          )}
                        >
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </td>
                      );
                    })}
                  </tr>
                  {isOpen && (
                    <tr id={detailId} className="border-t border-ay-border bg-surface-2/30">
                      <td colSpan={totalColumnCount} className="whitespace-normal px-3 py-3">
                        {renderExpanded!(r.original)}
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            {empty && (
              <tr>
                <td colSpan={totalColumnCount} className="px-2 py-4 text-center text-ay-muted">
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Phone (portrait): one card per row, label: value per mobile-visible column */}
      <div className="space-y-2 md:hidden">
        {bodyRows.map((r, i) => {
          const isOpen = expandable && expandedKeys.has(r.id);
          const detailId = expandable ? `${instanceId}-${r.id}-detail-m` : undefined;
          return (
            <div
              key={r.id}
              {...rowClickProps(r.original, i, r.id)}
              className={cn(
                'rounded border border-ay-border bg-surface-1 p-2 text-xs',
                rowInteractive && 'cursor-pointer',
                rowClassName?.(r.original),
              )}
            >
              {expandable && (
                <div className="mb-1 flex justify-end">
                  <button
                    type="button"
                    aria-expanded={isOpen}
                    aria-controls={detailId}
                    aria-label={isOpen ? 'Collapse details' : 'Expand details'}
                    onClick={(e) => {
                      e.stopPropagation();
                      toggleExpanded(r.id);
                    }}
                    className="inline-flex items-center justify-center rounded-sm text-ay-muted hover:text-accent focus-visible:outline focus-visible:outline-1 focus-visible:outline-accent"
                  >
                    <ChevronRight
                      aria-hidden="true"
                      className={cn('size-4 transition-transform', isOpen && 'rotate-90')}
                    />
                  </button>
                </div>
              )}
              <dl className="grid grid-cols-2 gap-x-2 gap-y-0.5">
                {mobileLeafCols.map((col) => {
                  const meta = col.columnDef.meta as AyColumnMeta<Row>;
                  const cell = r.getAllCells().find((c) => c.column.id === col.id);
                  return (
                    <div key={col.id} className="flex justify-between gap-2">
                      <dt className="inline-flex items-center gap-1 text-ay-muted">
                        {meta.mobileLabel}
                        {meta.help && <InfoTip text={meta.help} label={meta.mobileLabel} />}
                      </dt>
                      <dd className={cn('tabular-nums', meta.cellClassName?.(r.original))}>
                        {cell && flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </dd>
                    </div>
                  );
                })}
              </dl>
              {isOpen && (
                <div id={detailId} className="mt-2 border-t border-ay-border pt-2">
                  {renderExpanded!(r.original)}
                </div>
              )}
            </div>
          );
        })}
        {empty && <p className="text-center text-ay-muted">{emptyMessage}</p>}
      </div>

      {paginated && total > pageSize && (
        <div className="mt-1.5 flex items-center justify-between px-1 text-xs text-ay-muted">
          <span className="tabular-nums">
            {rangeStart}–{rangeEnd} of {total}
          </span>
          <span className="flex gap-1">
            <button
              type="button"
              onClick={() => table.previousPage()}
              disabled={!table.getCanPreviousPage()}
              className="rounded border border-ay-border px-2 py-0.5 hover:border-accent disabled:opacity-40"
            >
              Prev
            </button>
            <button
              type="button"
              onClick={() => table.nextPage()}
              disabled={!table.getCanNextPage()}
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
