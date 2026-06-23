import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../components/charts/CandleChart.tsx', () => ({ CandleChart: () => <div data-testid="candlechart" /> }));
vi.mock('../../api/charts.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/charts.ts')>();
  return {
    ...actual, // keep CHART_INTERVALS
    useCandles: () => ({
      data: { items: [{ exchange: 'NSE', tradingsymbol: 'NIFTY 50', interval: '1d', bucket: '2026-06-23T09:15:00+05:30', open: '100', high: '110', low: '95', close: '105', volume: 1000, oi: null, source: 'LIVE' }] },
      isLoading: false,
    }),
    useChartSignals: () => ({ data: [] }),
  };
});
vi.mock('../../api/backtests.ts', () => ({ useBacktestTrades: () => ({ data: { items: [] } }) }));

import { ChartsPage } from './ChartsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/charts']}>
        <ChartsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ChartsPage', () => {
  it('renders the toolbar and the candlestick chart, and loads a new symbol', () => {
    renderPage();
    expect(screen.getByLabelText('Interval')).toBeInTheDocument();
    expect(screen.getByTestId('candlechart')).toBeInTheDocument(); // candles present → chart renders

    const sym = screen.getByLabelText('Instrument') as HTMLInputElement;
    expect(sym.value).toBe('NSE:NIFTY 50');
    fireEvent.change(sym, { target: { value: 'NSE:TCS' } });
    fireEvent.click(screen.getByRole('button', { name: 'Load' }));
    expect((screen.getByLabelText('Instrument') as HTMLInputElement).value).toBe('NSE:TCS');
  });
});
