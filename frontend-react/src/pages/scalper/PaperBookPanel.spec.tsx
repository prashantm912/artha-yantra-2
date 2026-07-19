import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// The cockpit paper-book panel renders the operator's open positions + account P&L/exposure + the
// risk-limit guard, all from the EXISTING paper engine (no new backend). We mock the paper hooks and
// assert: (1) account header (equity/day P&L/exposure) + a position row with live uP&L, (2) the
// risk-limit chips reflect enabled/disabled state, (3) Close calls the close mutation, (4) the empty
// state copy shows when there are no positions.

const close = vi.fn();
const usePaperPositions = vi.fn();
const usePaperAccount = vi.fn();
const useRiskSettings = vi.fn();

vi.mock('../../api/paper.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/paper.ts')>();
  return {
    ...actual, // keep the real riskEnabled helper
    usePaperPositions: () => usePaperPositions(),
    usePaperAccount: () => usePaperAccount(),
    useRiskSettings: () => useRiskSettings(),
    useClosePosition: () => ({ mutate: close }),
  };
});
vi.mock('../../api/ticks.ts', () => ({ useLiveTicks: () => ({}) }));

import { PaperBookPanel } from './PaperBookPanel.tsx';

const account = {
  startingCapital: '1000000.00',
  cash: '900000.00',
  equity: '1010000.00',
  realized: '0',
  unrealized: '0',
  dayPnl: '2500.00',
  openPositions: 1,
  capitalUsed: '150000.00',
  usageByClass: {},
  marginPercents: {},
};

const position = {
  id: 3,
  exchange: 'NSE',
  tradingsymbol: 'INFY',
  side: 'SELL' as const,
  qty: 100,
  avgEntryPrice: '1500.00',
  markPrice: '1490.00',
  unrealizedPnl: '1000.00',
  realizedPnl: '0',
  status: 'OPEN',
  openedAt: '2026-06-24T09:30:00+05:30',
  stopLoss: '1520.00',
  takeProfit: '1450.00',
};

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PaperBookPanel />
    </QueryClientProvider>,
  );
}

describe('PaperBookPanel', () => {
  it('renders the account header, a live position row, the risk chips, and closes a position', () => {
    usePaperPositions.mockReturnValue({ data: { items: [position] } });
    usePaperAccount.mockReturnValue({ data: account });
    useRiskSettings.mockReturnValue({
      data: {
        items: [
          { key: 'kill_switch', value: { enabled: true }, updatedAt: '' },
          { key: 'max_open_paper_positions', value: { enabled: true, value: 5 }, updatedAt: '' },
          { key: 'daily_loss_limit', value: { enabled: false }, updatedAt: '' },
        ],
      },
    });
    renderPanel();

    // Account header — equity / day P&L / exposure (capital used) / open count (outside the table).
    expect(screen.getByText('1010000.00')).toBeInTheDocument();
    expect(screen.getByText('2500.00')).toBeInTheDocument();
    expect(screen.getByText('150000.00')).toBeInTheDocument();

    // The positions grid now renders through the shared DataTable (desktop <table> + md:hidden card
    // list) → each cell text exists twice in jsdom, so scope every table assertion to the named table.
    const grid = within(screen.getByRole('table', { name: 'Open positions' }));

    // Position row + server-marked unrealized P&L (no live tick → falls back to BE mark).
    expect(grid.getByText('NSE:INFY')).toBeInTheDocument();
    expect(grid.getByText('1000.00')).toBeInTheDocument();

    // Risk chips reflect on/off state (kill switch armed, daily loss off).
    expect(screen.getByText('Kill switch: on')).toBeInTheDocument();
    expect(screen.getByText('Max open: 5')).toBeInTheDocument();
    expect(screen.getByText('Daily loss ₹: off')).toBeInTheDocument();

    fireEvent.click(grid.getByRole('button', { name: 'Close' }));
    expect(close).toHaveBeenCalledWith({ id: 3 });
  });

  it('renders the positions grid header + row cell-for-cell (content preserved through adoption)', () => {
    usePaperPositions.mockReturnValue({ data: { items: [position] } });
    usePaperAccount.mockReturnValue({ data: account });
    useRiskSettings.mockReturnValue({ data: { items: [] } });
    renderPanel();

    const grid = within(screen.getByRole('table', { name: 'Open positions' }));
    expect(grid.getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Instrument',
      'Side',
      'Qty',
      'Mark',
      'uP&L',
      'SL / TP',
      'Actions',
    ]);
    const dataRow = grid.getAllByRole('row').slice(1)[0]; // [0] = header row
    expect(within(dataRow).getAllByRole('cell').map((c) => c.textContent)).toEqual([
      'NSE:INFY',
      'SELL',
      '100',
      '1490.00', // mtm mark = BE markPrice (no live tick)
      '1000.00', // SELL: (1500 − 1490) × 100
      '1520.00 / 1450.00',
      'Close',
    ]);
  });

  it('shows the empty state when there are no open positions', () => {
    usePaperPositions.mockReturnValue({ data: { items: [] } });
    usePaperAccount.mockReturnValue({ data: account });
    useRiskSettings.mockReturnValue({ data: { items: [] } });
    renderPanel();

    // DataTable paints the empty message in both the desktop table and the mobile card list.
    expect(screen.getAllByText(/No open positions —/).length).toBeGreaterThan(0);
    // With no risk settings, every chip reads off.
    expect(screen.getByText('Kill switch: off')).toBeInTheDocument();
  });

  it('defaults to the scalper book and swaps the risk chips for a per-book note on "All books" (M18)', () => {
    usePaperPositions.mockReturnValue({ data: { items: [position] } });
    usePaperAccount.mockReturnValue({ data: account });
    useRiskSettings.mockReturnValue({
      data: { items: [{ key: 'kill_switch', value: { enabled: true }, updatedAt: '' }] },
    });
    renderPanel();

    // Default scoping: the scalper book is named, and the per-book risk chip row is shown.
    expect(screen.getByText('Scalper book')).toBeInTheDocument();
    expect(screen.getByText('Kill switch: on')).toBeInTheDocument();

    // Switch to the all-books aggregate → the (un-aggregatable) risk chips give way to the note.
    fireEvent.change(screen.getByRole('combobox', { name: 'Paper book' }), {
      target: { value: 'all' },
    });
    expect(screen.getByText('All books (aggregate)')).toBeInTheDocument();
    expect(screen.queryByText('Kill switch: on')).not.toBeInTheDocument();
    expect(screen.getByText(/Risk limits are per-book/)).toBeInTheDocument();
  });
});
