import { describe, expect, it } from 'vitest';
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
  it('renders headers and rows', () => {
    renderTable();
    for (const h of ['Name', 'Price', 'Qty']) {
      expect(within(body()).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(names()).toEqual(['BBB', 'AAA', 'CCC']); // input order when unsorted
  });

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
});
