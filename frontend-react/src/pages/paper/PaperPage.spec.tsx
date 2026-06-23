import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { PaperAccount, PaperPnl, PaperPosition, PaperTrade, RiskSetting } from '../../api/paper.ts';

const account: PaperAccount = {
  startingCapital: '1000000.00',
  cash: '950000.00',
  equity: '1012500.00',
  realized: '12500.00',
  unrealized: '0.00',
  dayPnl: '2500.00',
  openPositions: 1,
  capitalUsed: '50000.00',
  usageByClass: {},
  marginPercents: {},
};
const positions: PaperPosition[] = [
  {
    id: 3,
    exchange: 'NSE',
    tradingsymbol: 'RELIANCE',
    side: 'BUY',
    qty: 50,
    avgEntryPrice: '1300.00',
    markPrice: '1326.00',
    unrealizedPnl: '1300.00',
    realizedPnl: '0.00',
    status: 'OPEN',
    openedAt: '2026-06-23T09:40:00+05:30',
  },
];
const trades: PaperTrade[] = [
  {
    id: 9,
    exchange: 'NSE',
    tradingsymbol: 'INFY',
    side: 'SELL',
    qty: 100,
    avgEntryPrice: '1500.00',
    realizedPnl: '-450.00',
    openedAt: '2026-06-22T10:00:00+05:30',
    closedAt: '2026-06-22T14:30:00+05:30',
  },
];
const pnl: PaperPnl = {
  points: [{ date: '2026-06-22', equity: '1000000' }, { date: '2026-06-23', equity: '1012500' }],
  summary: { realizedTotal: '12500.00', trades: 4, winRate: '0.75', expectancy: '3125.00' },
};
const risk: RiskSetting[] = [{ key: 'kill_switch', value: { enabled: false }, updatedAt: '2026-06-23T09:00:00+05:30' }];

const updateRisk = vi.fn();
const closePosition = vi.fn();
const resetLedger = vi.fn();

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
vi.mock('../../api/paper.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/paper.ts')>();
  return {
    ...actual, // keep riskEnabled (pure helper)
    usePaperPositions: () => ({ data: { items: positions } }),
    usePaperTrades: () => ({ data: { items: trades } }),
    usePaperPnl: () => ({ data: pnl }),
    usePaperAccount: () => ({ data: account }),
    useRiskSettings: () => ({ data: { items: risk } }),
    useUpdateCapital: () => ({ mutate: vi.fn() }),
    useUpdateRisk: () => ({ mutate: updateRisk }),
    useClosePosition: () => ({ mutate: closePosition }),
    useResetLedger: () => ({ mutate: resetLedger }),
  };
});

import { PaperPage } from './PaperPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <PaperPage />
    </QueryClientProvider>,
  );
}

describe('PaperPage', () => {
  it('renders account header, positions, ledger and summary, and wires the actions', () => {
    renderPage();
    // account + summary
    expect(screen.getByText('1012500.00')).toBeInTheDocument(); // equity
    expect(screen.getByText('75.0%')).toBeInTheDocument(); // win rate
    // open position
    expect(screen.getByText('NSE:RELIANCE')).toBeInTheDocument();
    // closed trade
    expect(screen.getByText('NSE:INFY')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Close'));
    expect(closePosition).toHaveBeenCalledWith({ id: 3 });

    fireEvent.click(screen.getByText(/Reset ledger/));
    expect(resetLedger).toHaveBeenCalled();

    fireEvent.click(screen.getByText(/Kill switch/));
    expect(updateRisk).toHaveBeenCalledWith({ key: 'kill_switch', value: { enabled: true } });
  });
});
