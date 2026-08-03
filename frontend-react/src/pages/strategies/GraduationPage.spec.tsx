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
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 3019ms in a full-suite run.
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
  }, 15_000);

  // a11y — WCAG 1.4.1 (Use of Color): the criterion dots must not encode pass/fail in COLOUR ALONE,
  // so each carries a glyph (✓/✗) a colour-blind sighted user can read.
  //
  // Assert against the ACCESSIBILITY TREE (getAllByRole + name), never the aria-label ATTRIBUTE: the
  // dot is a <span>, and ARIA PROHIBITS aria-label on the generic role, so on a bare span the name is
  // not guaranteed and the glyph can leak into it ("trades pass ✓"). role="img" is what makes the
  // label legal and the glyph presentational. An attribute assertion is green either way — it cannot
  // see the difference, which is the same blind-gate failure this dot's colour-only bug was.
  it('distinguishes a passing from a failing criterion by a glyph, not colour alone', () => {
    renderPage();
    const passing = board().getAllByRole('img', { name: /pass$/ });
    const failing = board().getAllByRole('img', { name: /fail$/ });
    expect(passing).toHaveLength(4);
    expect(failing).toHaveLength(4);
    // the glyph is the VISIBLE non-colour channel...
    expect(passing.map((d) => d.textContent)).toEqual(['✓', '✓', '✓', '✓']);
    expect(failing.map((d) => d.textContent)).toEqual(['✗', '✗', '✗', '✗']);
    // ...and it must NOT leak into the accessible name (role="img" makes descendants presentational),
    // nor may a passing dot read as failing.
    expect(board().queryAllByRole('img', { name: /✓|✗/ })).toHaveLength(0);
    expect(board().queryAllByRole('img', { name: /pass.*fail|fail.*pass/ })).toHaveLength(0);
    // the SR path keeps the detail-bearing tooltip alongside the name
    for (const dot of [...passing, ...failing]) {
      expect(dot).toHaveAttribute('title', expect.stringContaining('needs'));
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
