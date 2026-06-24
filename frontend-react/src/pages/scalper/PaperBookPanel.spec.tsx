import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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

    // Account header — equity / day P&L / exposure (capital used) / open count.
    expect(screen.getByText('1010000.00')).toBeInTheDocument();
    expect(screen.getByText('2500.00')).toBeInTheDocument();
    expect(screen.getByText('150000.00')).toBeInTheDocument();

    // Position row + server-marked unrealized P&L (no live tick → falls back to BE mark).
    expect(screen.getByText('NSE:INFY')).toBeInTheDocument();
    expect(screen.getByText('1000.00')).toBeInTheDocument();

    // Risk chips reflect on/off state (kill switch armed, daily loss off).
    expect(screen.getByText('Kill switch: on')).toBeInTheDocument();
    expect(screen.getByText('Max open: 5')).toBeInTheDocument();
    expect(screen.getByText('Daily loss ₹: off')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(close).toHaveBeenCalledWith({ id: 3 });
  });

  it('shows the empty state when there are no open positions', () => {
    usePaperPositions.mockReturnValue({ data: { items: [] } });
    usePaperAccount.mockReturnValue({ data: account });
    useRiskSettings.mockReturnValue({ data: { items: [] } });
    renderPanel();

    expect(screen.getByText(/No open positions —/)).toBeInTheDocument();
    // With no risk settings, every chip reads off.
    expect(screen.getByText('Kill switch: off')).toBeInTheDocument();
  });
});
