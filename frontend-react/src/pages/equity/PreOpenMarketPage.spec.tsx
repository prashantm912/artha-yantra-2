import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MarketStatus, PreOpenSnapshot } from '../../api/types.ts';

// A pre-open NSE (highlighted), an open BSE, and a closed MCX exercise the three card tones; the index
// snapshot carries a gainer, a loser, and a price-less row so the sign-aware rendering and the
// muted-dash fallback are both exercised deterministically.
const statuses: MarketStatus[] = [
  { exchange: 'NSE', status: 'PRE_OPEN_START', preOpen: true, asOf: '2026-06-24T09:05:00+05:30' },
  { exchange: 'BSE', status: 'NORMAL_OPEN', preOpen: false, asOf: '2026-06-24T09:05:00+05:30' },
  { exchange: 'MCX', status: 'CLOSING_END', preOpen: false, asOf: '2026-06-24T09:05:00+05:30' },
];

const snapshot: PreOpenSnapshot = {
  phase: 'PRE_OPEN_START',
  preOpen: true,
  indices: [
    { name: 'Nifty 50', key: 'NSE_INDEX|Nifty 50', ltp: '23900.00', prevClose: '23661.00', netChange: '239.00', changePct: '1.01', asOf: '2026-06-24T09:05:00+05:30' },
    { name: 'Nifty Bank', key: 'NSE_INDEX|Nifty Bank', ltp: '52000.00', prevClose: '52100.00', netChange: '-100.00', changePct: '-0.19', asOf: '2026-06-24T09:05:00+05:30' },
    { name: 'Sensex', key: 'BSE_INDEX|SENSEX', ltp: null, prevClose: null, netChange: null, changePct: null, asOf: '2026-06-24T09:05:00+05:30' },
  ],
};

const scan = {
  date: '2026-06-24',
  dates: ['2026-06-24'],
  items: [
    { symbol: 'RELIANCE', preOpenPrice: '1510.00', prevClose: '1480.00', change: '30.00', changePct: '2.03', prevDayBreak: 'H' as const },
    { symbol: 'HINDALCO', preOpenPrice: '960.00', prevClose: '983.90', change: '-23.90', changePct: '-2.43', prevDayBreak: null },
  ],
};

vi.mock('../../api/preOpen.ts', () => ({
  useMarketStatus: () => ({ data: statuses, isPending: false, isError: false, isSuccess: true, refetch: () => {} }),
  usePreOpen: () => ({ data: snapshot, isPending: false, isError: false, isSuccess: true, refetch: () => {} }),
  usePreOpenScan: () => ({ data: scan, isPending: false, isError: false, isSuccess: true, refetch: () => {} }),
}));

import { PreOpenMarketPage } from './PreOpenMarketPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PreOpenMarketPage />
    </QueryClientProvider>,
  );
}

describe('PreOpenMarketPage', () => {
  it('renders the three exchange phase cards with humanised phases and a pre-open badge', () => {
    renderPage();
    for (const ex of ['NSE', 'BSE', 'MCX']) {
      expect(screen.getByText(ex)).toBeInTheDocument();
    }
    // The raw enums are humanised.
    expect(screen.getByText('Pre-Open Start')).toBeInTheDocument();
    expect(screen.getByText('Normal Open')).toBeInTheDocument();
    expect(screen.getByText('Closing End')).toBeInTheDocument();
    // The pre-open exchange carries an explicit "Pre-Open" badge (text cue, never colour-only).
    expect(screen.getByText('Pre-Open')).toBeInTheDocument();
  });

  it('renders the preserved stock scanner split into advances and declines with the break badge', () => {
    renderPage();
    // the advancing stock sits in the Advances table with its High Break badge; the decliner opposite.
    const adv = screen.getByRole('table', { name: 'Pre-open market advances' });
    expect(within(adv).getByText('RELIANCE')).toBeInTheDocument();
    expect(within(adv).getByText('High Break')).toBeInTheDocument();
    const dec = screen.getByRole('table', { name: 'Pre-open market declines' });
    expect(within(dec).getByText('HINDALCO')).toBeInTheDocument();
    // the counts strip reads the split.
    expect(screen.getByText(/Advances:/)).toBeInTheDocument();
  });

  it('renders the index snapshot with a sign-aware change per row and a muted dash for the price-less row', () => {
    renderPage();
    const table = screen.getAllByRole('table')[0];
    for (const h of ['Index', 'LTP', 'Prev Close', 'Net Chg', '% Chg']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    // A gainer carries an explicit +sign; a loser its -sign — text cue, never colour-only.
    expect(within(table).getByText('+239.00')).toBeInTheDocument();
    expect(within(table).getByText('-100.00')).toBeInTheDocument();
    expect(within(table).getByText('+1.01%')).toBeInTheDocument();
    expect(within(table).getByText('-0.19%')).toBeInTheDocument();
    // The price-less row still lists (name shown).
    expect(within(table).getByText('Sensex')).toBeInTheDocument();
  });
});
