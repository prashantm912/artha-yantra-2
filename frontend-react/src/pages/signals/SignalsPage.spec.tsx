import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { SignalDto } from '../../api/signals.ts';

const baseSignal: SignalDto = {
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

// Mutable per-test state: the mock factory's arrow functions dereference these lazily (at render), so
// a test can vary the signal's status / the mutation's error state before calling renderPage().
let signal: SignalDto = baseSignal;
let dismissIsError = false;

const take = vi.fn();
const dismiss = vi.fn();

vi.mock('../../api/signals.ts', () => ({
  SIGNAL_RING_LIMIT: 200,
  SIGNAL_STATUSES: ['ACTIVE', 'EXPIRED', 'TAKEN', 'DISMISSED'],
  useSignals: () => ({ data: { items: [signal] }, isFetching: false, isLoading: false, refetch: () => {} }),
  useSignalsLive: () => {},
  useSignalDetail: () => ({ data: signal }),
  useTakeSignal: () => ({ mutate: take, isPending: false }),
  useDismissSignal: () => ({ mutate: dismiss, isPending: false, isError: dismissIsError }),
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
  beforeEach(() => {
    signal = baseSignal;
    dismissIsError = false;
    take.mockClear();
    dismiss.mockClear();
  });

  it('hides Dismiss for a TAKEN signal — discarding an open position\'s anchor would strand it', () => {
    // task_6f1372da third door: a TAKEN anchor holds an open paper position, and the engine resolves
    // that position's exit ONLY through an ACTIVE/TAKEN entry. Dismissing it strands the position
    // un-exitable, so the server 422s it — and an offered-then-refused button is its own hazard.
    signal = { ...baseSignal, status: 'TAKEN' };
    renderPage();
    fireEvent.click(screen.getByText('NSE:RELIANCE'));

    // The positive assertion is load-bearing: it proves the action panel actually rendered, so the
    // absence of Dismiss below is a real gate rather than a vacuously-empty page.
    expect(screen.getByText('✓ Taken')).toBeInTheDocument();
    expect(screen.queryByText('✕ Dismiss')).not.toBeInTheDocument();
  });

  it('surfaces a refused dismiss rather than failing silently', () => {
    dismissIsError = true;
    renderPage();
    fireEvent.click(screen.getByText('NSE:RELIANCE'));

    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't dismiss this signal/);
  });

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
