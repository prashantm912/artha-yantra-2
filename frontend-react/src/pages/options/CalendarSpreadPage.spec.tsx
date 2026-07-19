import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const q = <T,>(data: T) => ({
  isPending: false,
  isError: false,
  isSuccess: true,
  isFetching: false,
  data,
  refetch: () => {},
});

const CHAIN = { rows: [{ strike: '25000' }, { strike: '25100' }], spot: '25050', underlying: 'NIFTY 50' };
const SPREAD = {
  items: [{ time: 1, open: 1, high: 1, low: 1, close: 1 }],
  underlying: 'NIFTY 50',
  underlyingLtp: '25050.00',
  strike: '25000',
  optionType: 'CE',
  nearExpiry: '2026-07-31',
  farExpiry: '2026-08-28',
  asOf: '2026-07-19T09:30:00Z',
};

vi.mock('../../api/oiAnalytics.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/oiAnalytics.ts')>();
  return {
    ...actual,
    useChainTable: () => q(CHAIN),
    useCalendarSpreadChart: () => q(SPREAD),
  };
});
vi.mock('../../api/instruments.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/instruments.ts')>();
  return { ...actual, useExpiries: () => q(['2026-07-31', '2026-08-28']) };
});
vi.mock('../../stores/symbolContext.store.ts', () => ({
  useSymbolContext: (sel: (s: unknown) => unknown) =>
    sel({ name: 'NIFTY', expiry: '2026-07-31', setExpiry: () => {} }),
}));
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
vi.mock('../../components/CalendarSpreadChart.tsx', () => ({ CalendarSpreadChart: () => null }));

import { CalendarSpreadPage } from './CalendarSpreadPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <CalendarSpreadPage />
    </QueryClientProvider>,
  );
}

// The two-leg positions table renders through the shared DataTable (desktop <table> + md:hidden card
// list) — scope to the named desktop table.
const table = () => within(screen.getByRole('table', { name: 'Positions' }));

describe('CalendarSpreadPage positions table', () => {
  it('renders the columns in their declared order', () => {
    renderPage();
    expect(table().getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Leg',
      'Expiry',
      'Strike',
      'Type',
    ]);
  });

  it('renders the near + far legs cell-for-cell', () => {
    renderPage();
    const rows = table().getAllByRole('row').slice(1); // [0] = header row
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'Near',
      '2026-07-31',
      '25000',
      'CE',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'Far',
      '2026-08-28',
      '25000',
      'CE',
    ]);
  });
});
