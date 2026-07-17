import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
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

// The board + promotions tables render through the shared DataTable, which paints a desktop
// <table> AND a md:hidden mobile card list — so every cell text exists twice in jsdom (CSS
// doesn't apply). Scope each assertion to the desktop table so getByText stays exactly-one.
const board = () => within(screen.getByRole('table', { name: 'Graduation board' }));
const graduated = () => within(screen.getByRole('table', { name: 'Graduated strategies' }));

describe('GraduationPage', () => {
  it('renders each strategy with its stage badge, metrics and per-criterion dots', () => {
    renderPage();
    expect(screen.getByText('Graduation')).toBeInTheDocument();
    // both strategies + their stage badges
    expect(board().getByText('EMA Cross')).toBeInTheDocument();
    expect(board().getByText('RSI Dip')).toBeInTheDocument();
    expect(board().getByText('Take-eligible')).toBeInTheDocument();
    expect(board().getByText('Paper')).toBeInTheDocument();
    // the threshold summary strip
    expect(screen.getByText(/PF ≥ 1.30/)).toBeInTheDocument();
    // one dot per criterion, per strategy (4 + 4 = 8)
    expect(board().getAllByLabelText(/pass|fail/).length).toBe(8);
  });

  // a11y — WCAG 1.4.1 (Use of Color): the criterion dots must not encode pass/fail in COLOUR ALONE,
  // so each carries a glyph (✓/✗) a colour-blind sighted user can read. Our axe + role/name gates
  // can't catch this class: the accessible tree was already correct via aria-label, and the failure
  // was visual-only. Pin the glyph per state, and keep the aria-label/title SR path intact.
  it('distinguishes a passing from a failing criterion by a glyph, not colour alone', () => {
    renderPage();
    const dots = board().getAllByLabelText(/pass|fail/);
    expect(dots.length).toBe(8);
    for (const dot of dots) {
      const passed = dot.getAttribute('aria-label')?.endsWith('pass');
      expect(dot.textContent).toBe(passed ? '✓' : '✗');
      // the SR path stays the detail-bearing one (aria-label overrides the glyph as the name)
      expect(dot).toHaveAttribute('title', expect.stringContaining(passed ? 'pass' : 'fail'));
    }
  });

  it('renders the board columns in their declared order', () => {
    renderPage();
    expect(board().getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Strategy',
      'Stage',
      'Trades',
      'Net',
      'Win%',
      'PF',
      'Expectancy',
      'Max DD',
      'Criteria',
    ]);
  });

  it('renders a board row cell-for-cell in order', () => {
    renderPage();
    const row = board().getAllByRole('row')[1]; // [0] = the header row
    expect(within(row).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'EMA Crossema-crossDossier →',
      'Take-eligible',
      '24',
      '+₹1200.00',
      '62.5%',
      '1.80',
      '50.00',
      '8.50%',
      '✓✓✓✓', // 4 passing criteria — the glyph, not just the fill (see the 1.4.1 test above)
    ]);
  });

  it('surfaces the GRADUATED promotions section', () => {
    renderPage();
    expect(screen.getByText('Graduated')).toBeInTheDocument();
    // the promotion row for a strategy off the current board renders via its id fallback + snapshot
    expect(graduated().getByText('s3')).toBeInTheDocument();
    expect(graduated().getByText('2026-07-01')).toBeInTheDocument();
  });
});
