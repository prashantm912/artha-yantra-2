import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { HolidayRow } from '../../api/types.ts';

// A past date and a far-future date so the Passed/Coming split is deterministic regardless of run date.
const data: HolidayRow[] = [
  { date: '2020-01-26', day: 'Sunday', description: 'Republic Day' },
  { date: '2099-12-25', day: 'Friday', description: 'Christmas' },
];

vi.mock('../../api/holidays.ts', () => ({
  useHolidays: () => ({ data, isLoading: false }),
}));

import { MarketHolidaysPage } from './MarketHolidaysPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MarketHolidaysPage />
    </QueryClientProvider>,
  );
}

describe('MarketHolidaysPage', () => {
  it('renders the faithful columns and a Passed/Coming validity per date', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Date', 'Day', 'Description', 'Validity']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    // A long-past date reads Passed; a far-future date reads Coming.
    expect(within(table).getByText('Passed')).toBeInTheDocument();
    expect(within(table).getByText('Coming')).toBeInTheDocument();
  });
});
