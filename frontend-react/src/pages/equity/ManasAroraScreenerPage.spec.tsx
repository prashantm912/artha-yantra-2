import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ManasScreen } from '../../api/manasArora.ts';

const screenData: ManasScreen = {
  screenDate: '2026-07-03',
  coverage: 1590,
  limit: 200,
  offset: 0,
  items: [
    {
      symbol: 'SANGINITA',
      exchange: 'NSE',
      close: '412.5',
      sma50: '380',
      sma200: '300',
      high52w: '430',
      low52w: '190',
      avgVol20: '250000',
      avgVol50: '240000',
      turnover50: '9800000',
      withinHighPct: '-0.0407',
      aboveLowPct: '1.1710',
      withinHigh: true,
      aboveSma50: true,
      liquidVolume: true,
      liquidDepth: true,
      lowCap: true,
      freeFloatMcapCr: '1200',
      freeFloatPct: '28.4',
      gates: [true, true, true, true, true, true],
      gatesPassed: 6,
      passesAll: true,
    },
  ],
};

vi.mock('../../api/manasArora.ts', async (importActual) => {
  const actual = await importActual<typeof import('../../api/manasArora.ts')>();
  return {
    ...actual,
    useManasScreen: () => ({ data: screenData, isFetching: false, refetch: () => {} }),
    useManasFunnel: () => ({ data: undefined, isFetching: false }),
    useRunManas: () => ({ mutate: () => {}, isPending: false }),
  };
});

import { ManasAroraScreenerPage } from './ManasAroraScreenerPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ManasAroraScreenerPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ManasAroraScreenerPage', () => {
  it('renders the selection screen table with the passer row and its gates', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Symbol', 'Close', '% from high', '% above low', 'Gates']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    const link = screen.getByRole('link', { name: 'SANGINITA' });
    expect(link).toHaveAttribute('href', '/equity/manas-arora/SANGINITA');
    expect(screen.getByText(/1590 scanned/)).toBeInTheDocument();
  });
});
