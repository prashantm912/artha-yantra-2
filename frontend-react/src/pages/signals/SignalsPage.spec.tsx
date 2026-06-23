import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SignalDto } from '../../api/signals.ts';

const signal: SignalDto = {
  id: 7,
  strategyId: 'ema-cross',
  version: '1.2.0',
  checksum: 'abcdef0123456789',
  exchange: 'NSE',
  tradingsymbol: 'RELIANCE',
  interval: '5m',
  signalType: 'ENTRY',
  side: 'BUY',
  entryPrice: '1300.00',
  stopLoss: '1290.00',
  target: '1330.00',
  compositeScore: '0.7200',
  status: 'ACTIVE',
  generatedAt: '2026-06-23T09:30:15+05:30',
  suggestedQty: '50',
  scoreBreakdown: {
    composite: '0.72',
    threshold: '0.6',
    passed: true,
    requiredComposite: '0.5',
    optionalMinScore: '0.3',
    optionalGateMargin: '0.1',
    weightDenominator: '1.0',
    gate: { kind: 'AND', passed: true, children: [{ kind: 'leaf', rule: 'ema9 > ema21', passed: true }] },
    indicators: [
      {
        alias: 'ema',
        name: 'EMA cross',
        timeframe: '5m',
        score: '0.8',
        weight: '0.9',
        contribution: '0.72',
        optional: false,
        activated: true,
        activationReason: 'REQUIRED',
        rawValue: '12.3',
        params: {},
      },
    ],
  },
};

const take = vi.fn();
const dismiss = vi.fn();

vi.mock('../../api/signals.ts', () => ({
  SIGNAL_STATUSES: ['ACTIVE', 'EXPIRED', 'TAKEN', 'DISMISSED'],
  useSignals: () => ({ data: { items: [signal] }, isFetching: false, isLoading: false, refetch: () => {} }),
  useSignalsLive: () => {},
  useTakeSignal: () => ({ mutate: take, isPending: false }),
  useDismissSignal: () => ({ mutate: dismiss, isPending: false }),
}));

import { SignalsPage } from './SignalsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SignalsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SignalsPage', () => {
  it('lists the signal and shows reasoning on row select, then takes it with suggested qty', () => {
    renderPage();
    // feed row
    expect(screen.getByText('NSE:RELIANCE')).toBeInTheDocument();
    expect(screen.getByText('ENTRY BUY')).toBeInTheDocument();
    // placeholder before selection
    expect(screen.getByText(/Select a signal to see its reasoning/)).toBeInTheDocument();

    // select the row → reasoning breakdown renders
    fireEvent.click(screen.getByText('NSE:RELIANCE'));
    expect(screen.getByText('Composite vs threshold')).toBeInTheDocument();
    expect(screen.getByText('Gate checklist')).toBeInTheDocument();

    // take with the suggested qty (50)
    fireEvent.click(screen.getByText('✓ Taken'));
    expect(take).toHaveBeenCalledWith({ id: 7, qty: 50 });

    fireEvent.click(screen.getByText('✕ Dismiss'));
    expect(dismiss).toHaveBeenCalledWith(7);
  });
});
