import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ManasBacktestResult } from '../../api/manasArora.ts';

const RESULT = {
  status: 'completed',
  fromDate: '2015-01-01',
  runAt: '2026-07-10T00:00:00Z',
  note: 'note text',
  slotSweep: [],
  variants: [
    {
      status: 'completed',
      variant: 'rs-turnover-nopyramid',
      symbolsScanned: 500,
      totalTrades: 40,
      portfolioRsPriorityNet: {
        cagrPct: '12.34',
        totalReturnPct: '150.00',
        maxDrawdownPct: '22.10',
        sharpe: '0.96',
        positiveMonthsPct: '60.00',
      },
      setups: [
        {
          setup: 'breakout',
          trades: 20,
          winRatePct: '45.00',
          avgWinPct: '8.50',
          avgLossPct: '-3.20',
          payoffRatio: '2.10',
          expectancyPct: '1.50',
          profitFactor: '1.80',
          avgBarsHeld: '12',
          stopOutPct: '30.00',
        },
        {
          setup: 'ALL',
          trades: 40,
          winRatePct: '50.00',
          avgWinPct: '9.00',
          avgLossPct: '-3.00',
          payoffRatio: '2.20',
          expectancyPct: '2.00',
          profitFactor: '1.90',
          avgBarsHeld: '11',
          stopOutPct: '28.00',
        },
      ],
    },
    {
      status: 'completed',
      variant: 'technical',
      symbolsScanned: 500,
      totalTrades: 30,
      portfolioRsPriorityNet: {
        cagrPct: '-2.00',
        totalReturnPct: '-10.00',
        maxDrawdownPct: '40.00',
        sharpe: '0.10',
        positiveMonthsPct: '45.00',
      },
      setups: [
        {
          setup: 'vcp',
          trades: 30,
          winRatePct: '40.00',
          avgWinPct: '7.00',
          avgLossPct: '-4.00',
          payoffRatio: '1.60',
          expectancyPct: '-0.50',
          profitFactor: '0.90',
          avgBarsHeld: '9',
          stopOutPct: '35.00',
        },
      ],
    },
  ],
} as unknown as ManasBacktestResult;

vi.mock('../../api/manasArora.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/manasArora.ts')>();
  return {
    ...actual,
    useManasBacktestCompare: () => ({
      isPending: false,
      isError: false,
      isSuccess: true,
      data: RESULT,
      refetch: () => {},
    }),
    useRunManasBacktest: () => ({ mutate: () => {}, isPending: false }),
  };
});

import { ManasAroraBacktestPage } from './ManasAroraBacktestPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ManasAroraBacktestPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// Each grid renders through the shared DataTable (desktop <table> + md:hidden card list) — scope every
// assertion to the named desktop table.
const named = (name: string) => within(screen.getByRole('table', { name }));

describe('ManasAroraBacktestPage grids', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 5181ms in a full-suite run.
  it('renders the portfolio-comparison columns + rows cell-for-cell (live badge, signed %, — tone)', () => {
    renderPage();
    const t = named('Portfolio comparison');
    expect(t.getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Variant',
      'Trades',
      'CAGR',
      'Total ret',
      'Max DD',
      'Sharpe',
      '+Months',
    ]);
    const rows = t.getAllByRole('row').slice(1);
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'rs-turnover-nopyramidlive', // variant + the "live" primary badge concatenated
      '40',
      '+12.34%',
      '+150.00%',
      '22.10',
      '0.96',
      '60.00',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'technical',
      '30',
      '-2.00%',
      '-10.00%',
      '40.00',
      '0.10',
      '45.00',
    ]);
  }, 15_000);

  it('renders a per-setup grid cell-for-cell (breakout + ALL rows)', () => {
    renderPage();
    const t = named('rs-turnover-nopyramid per-setup stats');
    expect(t.getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Setup',
      'Trades',
      'Win %',
      'Expectancy',
      'Payoff',
      'Avg win',
      'Avg loss',
      'Profit factor',
      'Avg hold',
      'Stop-out %',
    ]);
    const rows = t.getAllByRole('row').slice(1);
    expect(within(rows[0]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'breakout',
      '20',
      '45.00',
      '+1.50%',
      '2.10',
      '8.50',
      '-3.20',
      '1.80',
      '12',
      '30.00',
    ]);
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'ALL',
      '40',
      '50.00',
      '+2.00%',
      '2.20',
      '9.00',
      '-3.00',
      '1.90',
      '11',
      '28.00',
    ]);
  });
});
