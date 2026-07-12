import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../api/graduation.ts', () => ({
  useGraduationBoard: () => ({
    data: {
      thresholds: { minTrades: 20, minProfitFactor: '1.3', minExpectancy: '0', maxDrawdownPct: '25' },
      asOf: '2026-07-04T00:00:00Z',
      strategies: [
        {
          strategyId: 's1',
          slug: 'ema-cross',
          name: 'EMA Cross',
          stage: 'TAKE_ELIGIBLE',
          trades: 24,
          netRealized: '1200.0000',
          winRate: '0.6250',
          profitFactor: '1.8000',
          expectancy: '50.0000',
          maxDrawdownPct: '8.5000',
          criteria: [
            { name: 'trades', required: '>= 20', actual: '24', pass: true },
            { name: 'profitFactor', required: '>= 1.3', actual: '1.8', pass: true },
            { name: 'expectancy', required: '> 0', actual: '50', pass: true },
            { name: 'maxDrawdownPct', required: '<= 25', actual: '8.5', pass: true },
          ],
        },
        {
          strategyId: 's2',
          slug: 'rsi-dip',
          name: 'RSI Dip',
          stage: 'PAPER',
          trades: 5,
          netRealized: '-300.0000',
          winRate: '0.2000',
          profitFactor: '0.4000',
          expectancy: '-60.0000',
          maxDrawdownPct: '30.0000',
          criteria: [
            { name: 'trades', required: '>= 20', actual: '5', pass: false },
            { name: 'profitFactor', required: '>= 1.3', actual: '0.4', pass: false },
            { name: 'expectancy', required: '> 0', actual: '-60', pass: false },
            { name: 'maxDrawdownPct', required: '<= 25', actual: '30', pass: false },
          ],
        },
      ],
    },
    isLoading: false,
    isError: false,
  }),
  // a graduated strategy no longer on the current board → renders via its id fallback (keeps the
  // board-row name assertions unique).
  useGraduationPromotions: () => ({
    data: [
      {
        strategyId: 's3',
        graduatedAt: '2026-07-01T00:00:00Z',
        trades: 30,
        expectancy: '45.0000',
        sharpe: '1.2000',
        maxDrawdownPct: '10.0000',
      },
    ],
  }),
}));

import { GraduationPage } from './GraduationPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <GraduationPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('GraduationPage', () => {
  it('renders each strategy with its stage badge, metrics and per-criterion dots', () => {
    renderPage();
    expect(screen.getByText('Graduation')).toBeInTheDocument();
    // both strategies + their stage badges
    expect(screen.getByText('EMA Cross')).toBeInTheDocument();
    expect(screen.getByText('RSI Dip')).toBeInTheDocument();
    expect(screen.getByText('Take-eligible')).toBeInTheDocument();
    expect(screen.getByText('Paper')).toBeInTheDocument();
    // the threshold summary strip
    expect(screen.getByText(/PF ≥ 1.30/)).toBeInTheDocument();
    // one dot per criterion, per strategy (4 + 4 = 8)
    expect(screen.getAllByLabelText(/pass|fail/).length).toBe(8);
  });

  it('surfaces the GRADUATED promotions section', () => {
    renderPage();
    expect(screen.getByText('Graduated')).toBeInTheDocument();
    // the promotion row for a strategy off the current board renders via its id fallback + snapshot
    expect(screen.getByText('s3')).toBeInTheDocument();
    expect(screen.getByText('2026-07-01')).toBeInTheDocument();
  });
});
