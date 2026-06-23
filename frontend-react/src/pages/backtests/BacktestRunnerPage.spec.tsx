import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const run = vi.fn();
const sweep = vi.fn();

vi.mock('../../api/strategies.ts', () => ({
  useStrategies: () => ({ data: { items: [{ id: 's1', name: 'EMA Cross' }] } }),
}));
vi.mock('../../api/backtests.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/backtests.ts')>();
  return { ...actual, useSubmitRun: () => ({ mutate: run, isPending: false }), useSubmitSweep: () => ({ mutate: sweep, isPending: false }) };
});

import { BacktestRunnerPage } from './BacktestRunnerPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <BacktestRunnerPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('BacktestRunnerPage', () => {
  it('runs a backtest once a strategy is picked, and the sweep tab reveals sweep params', () => {
    renderPage();
    const runBtn = screen.getByRole('button', { name: /Run backtest/ });
    expect(runBtn).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Strategy'), { target: { value: 's1' } });
    expect(runBtn).toBeEnabled();
    fireEvent.click(runBtn);
    expect(run).toHaveBeenCalledWith(expect.objectContaining({ strategyId: 's1' }), expect.anything());

    fireEvent.click(screen.getByRole('tab', { name: 'sweep' }));
    expect(screen.getByLabelText('Method')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Launch sweep/ }));
    expect(sweep).toHaveBeenCalledWith(expect.objectContaining({ strategyId: 's1', maxTrials: 30 }), expect.anything());
  });
});
