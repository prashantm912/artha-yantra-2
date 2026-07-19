import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../components/atoms/EChart.tsx', () => ({ EChart: () => null }));
const exportMocks = vi.hoisted(() => ({
  trades: vi.fn(),
  folds: vi.fn(),
  equity: vi.fn(),
}));
// Folds hook result is per-test overridable (FE-03): the default is a one-fold success; the error
// test flips it to an isError result to prove the tab surfaces the failure instead of hiding it.
const foldsMock = vi.hoisted(() => {
  const success = () => ({
    data: [
      {
        fold: { index: 1, trainFrom: '2026-01-01', trainTo: '2026-03-01', testFrom: '2026-03-01', testTo: '2026-04-01' },
        trainMetrics: {},
        oosMetrics: { sharpe: '1.2' },
      },
    ],
  });
  return { success, result: success() as ReturnType<typeof success> | { isError: true; refetch: () => void } };
});
vi.mock('../../api/backtestExport.ts', () => ({
  exportTrades: exportMocks.trades,
  exportFolds: exportMocks.folds,
  exportEquity: exportMocks.equity,
}));
vi.mock('../../api/backtests.ts', () => ({
  useBacktestResults: () => ({
    data: {
      metrics: { sharpe: '1.85', totalReturn: '12.34', tradeCount: '42', maxDrawdown: '-5.6' },
      equityCurve: [{ ts: '2026-06-01T00:00:00Z', value: '100000' }, { ts: '2026-06-02T00:00:00Z', value: '101000' }],
      dataHash: 'deadbeef',
      seed: 42,
      engineSha: 'cafebabe1234567890',
      engineImage: 'backtest-service:9.9.9',
    },
    isLoading: false,
  }),
  useBacktestTrades: () => ({ data: { items: [{ seq: 1, side: 'LONG', entryTs: '2026-06-01T09:30', entryPrice: '100.00', exitPrice: '110.00', pnl: '10.00', pnlPct: '10.00', exitReason: 'TARGET', barsHeld: 5, qty: 1 }] } }),
  useBacktestFolds: () => foldsMock.result,
  useBacktestMonteCarlo: () => ({
    data: { n: 1000, trades: 42, insufficientSample: false, equityBands: { step: [0, 1], p5: ['100', '95'], p50: ['100', '105'], p95: ['100', '115'] }, drawdownDistribution: { p5: '-2', p50: '-5', p95: '-10', mean: '-6' }, riskOfRuin: '0.02' },
  }),
  useOiAttribution: () => ({
    data: {
      underlying: 'NIFTY 50',
      interval: '5m',
      tradeCount: 3,
      tradesAttributed: 2,
      tradesNoData: 1,
      sessionsCovered: 1,
      sessionsUncovered: 1,
      caveat: 'Historical OI-confluence join over captured snapshots.',
      buckets: [
        { trend: 4, label: 'Ext.Bullish', count: 2, wins: 2, winRate: '1.0000', totalPnl: '500.00', avgPnl: '250.00' },
        { trend: -1, label: 'NO_DATA', count: 1, wins: 0, winRate: '0.0000', totalPnl: '-100.00', avgPnl: '-100.00' },
      ],
      trades: [],
    },
    isLoading: false,
  }),
}));

import { BacktestResultsPage } from './BacktestResultsPage.tsx';
import { exitReasonBreakdown } from './exitReasonBreakdown.ts';
import type { TradeRow } from '../../api/backtests.ts';

function trade(exitReason: string, pnl: string): TradeRow {
  return { seq: 0, side: 'LONG', qty: 1, entryTs: '2026-01-01T00:00', entryPrice: '100', exitPrice: '101', pnl, pnlPct: '1', exitReason, barsHeld: 1 };
}

describe('exitReasonBreakdown', () => {
  it('aggregates count, total/avg P&L and win-rate per reason, sorted by count desc', () => {
    const out = exitReasonBreakdown([
      trade('STOP_LOSS', '-50'),
      trade('STOP_LOSS', '-30'),
      trade('TAKE_PROFIT', '120'),
      trade('TAKE_PROFIT', '80'),
      trade('TAKE_PROFIT', '-20'),
    ]);
    expect(out.map((s) => s.reason)).toEqual(['TAKE_PROFIT', 'STOP_LOSS']);
    expect(out[0]).toMatchObject({ count: 3, wins: 2, totalPnl: '180', avgPnl: '60.0000' });
    expect(out[0].winRate).toBeCloseTo((2 / 3) * 100);
    expect(out[1]).toMatchObject({ count: 2, wins: 0, totalPnl: '-80', avgPnl: '-40.0000', winRate: 0 });
  });

  it('counts pnl == 0 as a win and maps a blank reason to —', () => {
    const out = exitReasonBreakdown([trade('', '0')]);
    expect(out).toEqual([{ reason: '—', count: 1, wins: 1, totalPnl: '0', winRate: 100, avgPnl: '0.0000' }]);
  });

  it('returns [] for no trades', () => {
    expect(exitReasonBreakdown([])).toEqual([]);
  });
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/backtests/run-1']}>
        <Routes>
          <Route path="/backtests/:id" element={<BacktestResultsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BacktestResultsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    foldsMock.result = foldsMock.success();
  });

  it('shows metrics, trades, folds and the Monte Carlo tab', () => {
    renderPage();
    expect(screen.getByText('Sharpe')).toBeInTheDocument();
    expect(screen.getByText('1.85')).toBeInTheDocument();
    expect(screen.getByText(/dataHash deadbeef/)).toBeInTheDocument();
    // Audit P0-2 / R1: the engine SHA renders (truncated to 12) when the run carries one.
    expect(screen.getByText('cafebabe1234')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Trades' }));
    // Exit-reason breakdown summarises the loaded trades above the table (one TARGET bucket here). It
    // rides the shared DataTable, which paints a desktop <table> AND an md:hidden card list — so scope
    // every assertion to the named desktop table (a bare getByText would match the card twin too).
    const breakdown = screen.getByRole('table', { name: 'Exit-reason breakdown' });
    const reasonCell = within(breakdown).getByText('TARGET');
    expect(reasonCell).toBeInTheDocument();
    const row = reasonCell.closest('tr')!;
    expect(within(row).getByText('100.0%')).toBeInTheDocument(); // 1/1 wins
    // The per-trade table (still a hand-rolled table, not a DataTable) also lists the trade's reason.
    const perTrade = screen.getAllByRole('table').find((t) => t !== breakdown)!;
    expect(within(perTrade).getByText('TARGET')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Folds' }));
    const foldsTable = screen.getByRole('table', { name: 'Walk-forward folds' });
    expect(within(foldsTable).getByText(/2026-03-01 → 2026-04-01/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Monte Carlo' }));
    expect(screen.getByText(/risk of ruin 0.02/)).toBeInTheDocument();
  });

  it('surfaces a folds fetch error instead of a false-empty "no folds" (FE-03)', () => {
    // A fold-fetch 500 used to be swallowed to [] → the Folds tab silently vanished, reading as a
    // genuine no-walk-forward run. It must now show the tab AND the shared error state.
    foldsMock.result = { isError: true, refetch: vi.fn() };
    renderPage();
    fireEvent.click(screen.getByRole('tab', { name: 'Folds' }));
    expect(screen.getByTestId('qs-error')).toBeInTheDocument();
    expect(screen.getByText("Couldn't load walk-forward folds")).toBeInTheDocument();
  });

  it('OI Attribution tab buckets trades by entry confluence and shows coverage', () => {
    renderPage();
    fireEvent.click(screen.getByRole('tab', { name: 'OI Attribution' }));
    expect(screen.getByText(/2\/3 trades scored/)).toBeInTheDocument();
    const buckets = screen.getByRole('table', { name: 'OI-confluence attribution buckets' });
    const row = within(buckets).getByText('Ext.Bullish').closest('tr')!;
    expect(within(row).getByText('1.000')).toBeInTheDocument(); // win rate
  });

  it('downloads equity, trades and folds in CSV and JSON formats', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Download equity as CSV' }));
    fireEvent.click(screen.getByRole('button', { name: 'Download equity as JSON' }));
    expect(exportMocks.equity).toHaveBeenNthCalledWith(1, 'run-1', 'csv');
    expect(exportMocks.equity).toHaveBeenNthCalledWith(2, 'run-1', 'json');

    fireEvent.click(screen.getByRole('tab', { name: 'Trades' }));
    fireEvent.click(screen.getByRole('button', { name: 'Download trades as CSV' }));
    fireEvent.click(screen.getByRole('button', { name: 'Download trades as JSON' }));
    expect(exportMocks.trades).toHaveBeenNthCalledWith(1, 'run-1', 'csv');
    expect(exportMocks.trades).toHaveBeenNthCalledWith(2, 'run-1', 'json');

    fireEvent.click(screen.getByRole('tab', { name: 'Folds' }));
    fireEvent.click(screen.getByRole('button', { name: 'Download folds as CSV' }));
    fireEvent.click(screen.getByRole('button', { name: 'Download folds as JSON' }));
    expect(exportMocks.folds).toHaveBeenNthCalledWith(1, 'run-1', 'csv');
    expect(exportMocks.folds).toHaveBeenNthCalledWith(2, 'run-1', 'json');
  });
});
