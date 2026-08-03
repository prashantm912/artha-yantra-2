import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { DataTable, type DataColumn } from './DataTable.tsx';

interface Row {
  name: string;
  price: string; // decimal string
  qty: number;
}

const ROWS: Row[] = [
  { name: 'BBB', price: '10.20', qty: 3 },
  { name: 'AAA', price: '9.50', qty: 1 },
  { name: 'CCC', price: '100.00', qty: 2 },
];

const COLUMNS: DataColumn<Row>[] = [
  { id: 'name', header: 'Name', align: 'left', sortValue: (r) => r.name, sortType: 'text', render: (r) => r.name, mobileLabel: 'Symbol' },
  { id: 'price', header: 'Price', sortValue: (r) => r.price, sortType: 'decimal', render: (r) => r.price, cellClassName: () => 'price-cell' },
  { id: 'qty', header: 'Qty', render: (r) => r.qty }, // no sortValue → not sortable
];

function body(): HTMLElement {
  return screen.getByRole('table');
}

// First-column text of each desktop body row, in render order.
function names(): string[] {
  const rows = within(body()).getAllByRole('row').slice(1); // drop the header row
  return rows.map((r) => within(r).getAllByRole('cell')[0]?.textContent ?? '');
}

function renderTable(props: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) {
  return render(
    <DataTable
      columns={COLUMNS}
      rows={ROWS}
      rowKey={(r) => r.name}
      ariaLabel="Test table"
      {...props}
    />,
  );
}

describe('DataTable', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3082ms in a full-suite run.
  it('renders headers and rows', () => {
    renderTable();
    for (const h of ['Name', 'Price', 'Qty']) {
      expect(within(body()).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(names()).toEqual(['BBB', 'AAA', 'CCC']); // input order when unsorted
  }, 15_000);

  it('sorts descending on first header click, ascending on the second', () => {
    renderTable();
    const priceHeader = within(body()).getByRole('button', { name: /Price/ });
    fireEvent.click(priceHeader); // desc
    expect(names()).toEqual(['CCC', 'BBB', 'AAA']); // 100 > 10.2 > 9.5
    fireEvent.click(priceHeader); // asc
    expect(names()).toEqual(['AAA', 'BBB', 'CCC']);
  });

  it('sorts decimal strings numerically, not lexicographically', () => {
    renderTable({ initialSort: { id: 'price', dir: 'asc' } });
    // Lexicographic would put '100.00' before '9.50'; decimal sort must not.
    expect(names()).toEqual(['AAA', 'BBB', 'CCC']); // 9.5 < 10.2 < 100
  });

  it('reflects the active sort via aria-sort', () => {
    renderTable();
    fireEvent.click(within(body()).getByRole('button', { name: /Name/ }));
    const nameHeader = within(body()).getByRole('columnheader', { name: /Name/ });
    expect(nameHeader).toHaveAttribute('aria-sort', 'descending');
    expect(within(body()).getByRole('columnheader', { name: /Price/ })).toHaveAttribute('aria-sort', 'none');
  });

  it('does not make a column without sortValue sortable', () => {
    renderTable();
    const qtyHeader = within(body()).getByRole('columnheader', { name: 'Qty' });
    expect(within(qtyHeader).queryByRole('button')).toBeNull();
    expect(qtyHeader).not.toHaveAttribute('aria-sort');
  });

  it('paginates with range text and Prev/Next bounds', () => {
    renderTable({ pageSize: 2 });
    expect(screen.getByText('1–2 of 3')).toBeInTheDocument();
    expect(names()).toEqual(['BBB', 'AAA']);
    const prev = screen.getByRole('button', { name: 'Prev' });
    const next = screen.getByRole('button', { name: 'Next' });
    expect(prev).toBeDisabled();
    expect(next).toBeEnabled();
    fireEvent.click(next);
    expect(screen.getByText('3–3 of 3')).toBeInTheDocument();
    expect(names()).toEqual(['CCC']);
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('applies cellClassName to cells', () => {
    const { container } = renderTable();
    expect(container.querySelectorAll('td.price-cell').length).toBeGreaterThan(0);
  });

  it('shows the empty message when there are no rows', () => {
    renderTable({ rows: [], emptyMessage: 'Nothing here' });
    expect(screen.getAllByText('Nothing here').length).toBeGreaterThan(0);
  });

  it('renders mobile-card labels only for columns with mobileLabel', () => {
    renderTable();
    // 'Symbol' is the mobileLabel for the name column; 'Price'/'Qty' have none → no card <dt>.
    expect(screen.getAllByText('Symbol').length).toBe(ROWS.length);
  });

  // --- onRowClick (opt-in row-click; audit M23 — mouse-only convenience, keyboard via in-cell controls) ---

  it('fires onRowClick from a desktop-row mouse click while the row KEEPS table `row` semantics (no button role)', () => {
    const onRowClick = vi.fn();
    renderTable({ onRowClick });
    // Audit M23: a role="button" on a <tr> strips its cells of their required `row` parent, so the
    // desktop row must stay role="row" — getAllByRole('row') therefore sees every data row.
    const rows = within(body()).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(ROWS.length);
    for (const r of rows) {
      expect(r).not.toHaveAttribute('role'); // implicit `row`, NOT overridden to button
      expect(r).not.toHaveAttribute('tabindex');
    }
    fireEvent.click(rows[0]); // BBB = first data row (index 0)
    expect(onRowClick).toHaveBeenCalledTimes(1);
    expect(onRowClick).toHaveBeenNthCalledWith(1, ROWS[0], 0);
  });

  it('introduces NO role="button" (desktop or mobile) — the M23 + nested-interactive guard — yet the mobile card still fires onRowClick on a mouse click', () => {
    const onRowClick = vi.fn();
    const { container } = renderTable({ onRowClick });
    // onRowClick must not turn any row/card into a button: not the <tr> (aria-required-parent) and
    // not the mobile card (it renders the same cells → a card-button would nest an in-cell control).
    expect(screen.queryByRole('button', { name: /BBB/ })).toBeNull();
    // The mobile card list is the md:hidden container; its cards are plain <div>s with a mouse onClick.
    const card = container.querySelector('.md\\:hidden > div');
    expect(card).not.toBeNull();
    fireEvent.click(card as Element); // first card = BBB (index 0)
    expect(onRowClick).toHaveBeenCalledTimes(1);
    expect(onRowClick).toHaveBeenNthCalledWith(1, ROWS[0], 0);
  });

  it('leaves rows plain and non-interactive (no role/tabindex/button anywhere) when onRowClick is unset', () => {
    renderTable();
    const rows = within(body()).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(ROWS.length);
    for (const r of rows) {
      expect(r).not.toHaveAttribute('role');
      expect(r).not.toHaveAttribute('tabindex');
    }
    // No row buttons on desktop OR mobile — only the two sortable-header buttons (Name, Price).
    expect(screen.queryByRole('button', { name: /BBB/ })).toBeNull();
  });

  // --- maxHeight (scroll-cap override) ---

  it('caps the desktop scroll region at max-h-[68vh] by default (no inline height)', () => {
    renderTable();
    const region = screen.getByRole('region', { name: 'Test table' });
    expect(region.className).toContain('max-h-[68vh]');
    expect(region.style.maxHeight).toBe('');
  });

  it('applies a custom maxHeight inline and drops the default cap when set', () => {
    renderTable({ maxHeight: '24rem' });
    const region = screen.getByRole('region', { name: 'Test table' });
    expect(region.style.maxHeight).toBe('24rem');
    expect(region.className).not.toContain('max-h-[68vh]');
  });
});

// --- renderExpanded (opt-in expandable rows; audit M23 — the expander is a real in-cell <button>,
//     NEVER role="button" on the <tr>) ---
describe('DataTable — expandable rows (renderExpanded)', () => {
  const renderExpanded = (r: Row) => <div>detail for {r.name}</div>;

  it('adds a leading expander <button aria-expanded> per row, keeping the <tr> a plain table row (no role=button)', () => {
    renderTable({ renderExpanded });
    const rows = within(body()).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(ROWS.length);
    for (const r of rows) {
      expect(r).not.toHaveAttribute('role'); // implicit `row`, never overridden to button (M23)
      expect(r).not.toHaveAttribute('tabindex');
    }
    const buttons = within(body()).getAllByRole('button', { name: 'Expand details' });
    expect(buttons).toHaveLength(ROWS.length);
    for (const b of buttons) {
      expect(b.tagName).toBe('BUTTON'); // a real, keyboard-focusable <button> (the AT control)
      expect(b).toHaveAttribute('aria-expanded', 'false');
      expect(b).toHaveAttribute('aria-controls'); // wired to the (not-yet-rendered) detail id
    }
  });

  it('toggles the inline detail row open/closed from the expander button and wires aria-controls to the detail id', () => {
    renderTable({ renderExpanded });
    expect(within(body()).queryByText('detail for BBB')).toBeNull();

    fireEvent.click(within(body()).getAllByRole('button', { name: 'Expand details' })[0]);

    const collapseBtn = within(body()).getAllByRole('button', { name: 'Collapse details' })[0];
    expect(collapseBtn).toHaveAttribute('aria-expanded', 'true');
    const controlledId = collapseBtn.getAttribute('aria-controls')!;
    const detailRow = document.getElementById(controlledId);
    expect(detailRow).not.toBeNull();
    expect(within(detailRow as HTMLElement).getByText('detail for BBB')).toBeInTheDocument();

    fireEvent.click(collapseBtn); // toggle back
    expect(within(body()).queryByText('detail for BBB')).toBeNull();
  });

  it('spans the detail cell across every column including the expander (colSpan = data columns + 1)', () => {
    renderTable({ renderExpanded });
    fireEvent.click(within(body()).getAllByRole('button', { name: 'Expand details' })[0]);
    const cell = within(body()).getByText('detail for BBB').closest('td');
    expect(cell).toHaveAttribute('colspan', String(COLUMNS.length + 1));
  });

  it('also toggles from a desktop-row mouse click as a convenience (button stopPropagations, so no double-toggle)', () => {
    renderTable({ renderExpanded });
    const firstRow = within(body()).getAllByRole('row').slice(1)[0];
    fireEvent.click(firstRow); // mouse-only convenience, since no onRowClick is set
    expect(within(body()).getByText('detail for BBB')).toBeInTheDocument();
  });

  it('renders NO expander column, detail row, or expander button when renderExpanded is omitted (backward-compat)', () => {
    renderTable();
    // Header count is exactly the data columns — no leading expander header cell.
    expect(within(body()).getAllByRole('columnheader')).toHaveLength(COLUMNS.length);
    expect(names()).toEqual(['BBB', 'AAA', 'CCC']); // unchanged data-cell order/content
    expect(screen.queryByRole('button', { name: /Expand details|Collapse details/ })).toBeNull();
  });
});
