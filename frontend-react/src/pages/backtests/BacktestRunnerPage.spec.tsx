import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const run = vi.fn();
const sweep = vi.fn();

vi.mock('../../api/strategies.ts', () => ({
  useStrategies: () => ({ data: { items: [{ id: 's1', name: 'EMA Cross' }] } }),
  useStrategyVersions: () => ({ data: { items: [{ version: '1.0.1', status: 'draft' }, { version: '1.0.0', status: 'published' }] } }),
}));
vi.mock('../../api/backtests.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/backtests.ts')>();
  return { ...actual, useSubmitRun: () => ({ mutate: run, isPending: false }), useSubmitSweep: () => ({ mutate: sweep, isPending: false }) };
});

import { BacktestRunnerPage } from './BacktestRunnerPage.tsx';

function renderPage(state?: unknown) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={state ? [{ pathname: '/backtests/run', state }] : undefined}>
        <BacktestRunnerPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BacktestRunnerPage', () => {
  beforeEach(() => {
    run.mockClear();
    sweep.mockClear();
  });

  it('runs a backtest once a strategy is picked, and the sweep tab reveals sweep params', () => {
    renderPage();
    const runBtn = screen.getByRole('button', { name: /Run backtest/ });
    expect(runBtn).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Strategy'), { target: { value: 's1' } });
    expect(runBtn).toBeEnabled();
    fireEvent.click(runBtn);
    expect(run).not.toHaveBeenCalled();
    const dialog = screen.getByRole('dialog', { name: 'Confirm backtest' });
    expect(dialog).toBeInTheDocument();
    expect(within(dialog).getByText('EMA Cross')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm & run' }));
    // No version picked ⇒ "Latest (default)" ⇒ strategyVersion omitted from the payload.
    expect(run).toHaveBeenCalledWith(
      expect.not.objectContaining({ strategyVersion: expect.anything() }),
      expect.anything(),
    );
    expect(run).toHaveBeenCalledWith(expect.objectContaining({ strategyId: 's1' }), expect.anything());

    // Pick an explicit older version ⇒ it rides in the payload.
    fireEvent.change(screen.getByLabelText('Version'), { target: { value: '1.0.0' } });
    fireEvent.click(runBtn);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm & run' }));
    expect(run).toHaveBeenLastCalledWith(
      expect.objectContaining({ strategyId: 's1', strategyVersion: '1.0.0' }),
      expect.anything(),
    );

    fireEvent.click(screen.getByRole('tab', { name: 'sweep' }));
    expect(screen.getByLabelText('Method')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Launch sweep/ }));
    expect(sweep).not.toHaveBeenCalled();
    const sweepDialog = screen.getByRole('dialog', { name: 'Confirm sweep' });
    expect(sweepDialog).toBeInTheDocument();
    expect(within(sweepDialog).getByText('tpe')).toBeInTheDocument();
    expect(within(sweepDialog).getByText('30')).toBeInTheDocument();
    expect(within(sweepDialog).getByText(/sharpe.*maximize.*mean/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm & run' }));
    expect(sweep).toHaveBeenCalledWith(expect.objectContaining({ strategyId: 's1', maxTrials: 30 }), expect.anything());
  });

  it('cancels the confirmation without submitting', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Strategy'), { target: { value: 's1' } });
    fireEvent.click(screen.getByRole('button', { name: /Run backtest/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(run).not.toHaveBeenCalled();
  });

  it('seeds the editable form from clone router state', () => {
    renderPage({
      clone: {
        strategyId: 's1', strategyVersion: '1.0.0', interval: '5m',
        from: '2026-05-01T00:00:00.000Z', to: '2026-06-01T00:00:00.000Z',
        initialCapital: '250000', seed: 7,
      },
    });
    expect(screen.getByLabelText('Strategy')).toHaveValue('s1');
    expect(screen.getByLabelText('Version')).toHaveValue('1.0.0');
    expect(screen.getByLabelText('Interval')).toHaveValue('5m');
    expect(screen.getByLabelText('From')).toHaveValue('2026-05-01');
    expect(screen.getByLabelText('To')).toHaveValue('2026-06-01');
    expect(screen.getByLabelText('Initial capital')).toHaveValue(250000);
    expect(screen.getByLabelText('Seed')).toHaveValue(7);
  });
});
