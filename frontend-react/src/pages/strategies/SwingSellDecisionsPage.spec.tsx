import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { RecordedSellDecision, SwingSellReport } from '../../api/swing.ts';

const apiFetch = vi.fn();

vi.mock('../../api/client.ts', async (importActual) => {
  const actual = await importActual<typeof import('../../api/client.ts')>();
  return { ...actual, apiFetch: (...args: unknown[]) => apiFetch(...args) };
});

const report: SwingSellReport = {
  asOf: '2026-07-09T20:15:00+05:30',
  items: [
    {
      signalId: 101,
      symbol: 'TCS',
      setup: 'minervini-vcp',
      stage: 2,
      footprint: '3T 12w',
      entryPrice: '3800',
      currentPrice: '4180',
      unrealizedPct: '10.00',
      stopLevel: '3496',
      trailLevel: '3990',
      stillBuyable: true,
      sellingNow: false,
      sellReason: null,
      verdict: 'HOLD',
    },
    {
      signalId: 102,
      symbol: 'INFY',
      setup: 'minervini-cheat',
      stage: 2,
      footprint: null,
      entryPrice: '1600',
      currentPrice: '1472',
      unrealizedPct: '-8.00',
      stopLevel: '1472',
      trailLevel: null,
      stillBuyable: false,
      sellingNow: true,
      sellReason: 'STOP_LOSS',
      verdict: 'SELL (STOP_LOSS)',
    },
  ],
};

const recorded: { items: RecordedSellDecision[] } = {
  items: [
    {
      id: 7,
      book: 'minervini',
      runDate: '2026-07-09',
      evaluatedAt: '2026-07-09T20:15:00+05:30',
      signalId: 102,
      exchange: 'NSE',
      symbol: 'INFY',
      setup: 'minervini-cheat',
      stage: 2,
      footprint: null,
      entryPrice: '1600',
      currentPrice: '1472',
      unrealizedPct: '-8.00',
      stopLevel: '1472',
      trailLevel: null,
      stillBuyable: false,
      sellingNow: true,
      sellReason: 'STOP_LOSS',
      verdict: 'SELL (STOP_LOSS)',
      acknowledgedAt: null,
    },
  ],
};

const ackMutate = vi.fn();

vi.mock('../../api/swing.ts', async (importActual) => {
  const actual = await importActual<typeof import('../../api/swing.ts')>();
  return {
    ...actual,
    useSwingSellDecisions: () => ({
      data: report,
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: () => {},
    }),
    useRecordedSellDecisions: () => ({
      data: recorded,
      isPending: false,
      isError: false,
      isSuccess: true,
      refetch: () => {},
    }),
    useAckSellDecision: () => ({ mutate: ackMutate, isPending: false }),
  };
});

import { SwingSellDecisionsPage } from './SwingSellDecisionsPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SwingSellDecisionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-07-27T08:00:00+05:30'));
  ackMutate.mockReset();
  apiFetch.mockReset();
  apiFetch.mockResolvedValue({});
});

afterEach(() => {
  vi.useRealTimers();
});

describe('SwingSellDecisionsPage', () => {
  it('renders the sell-decision table with a HOLD row and a SELL row', () => {
    renderPage();
    const table = screen.getByRole('table');
    for (const h of ['Symbol', 'Setup', 'Unrealized', 'Stop', 'Verdict']) {
      expect(within(table).getByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    }
    expect(within(table).getByText('TCS')).toBeInTheDocument();
    expect(within(table).getByText('INFY')).toBeInTheDocument();
    // the exit doctrine fired on INFY → the verdict badge carries the reason
    expect(within(table).getByText('SELL (STOP_LOSS)')).toBeInTheDocument();
    expect(within(table).getByText('HOLD')).toBeInTheDocument();
    // signed unrealized P&L, both directions
    expect(within(table).getByText('+10.00%')).toBeInTheDocument();
    expect(within(table).getByText('-8.00%')).toBeInTheDocument();
  });

  it('renders the live columns in their declared order', () => {
    renderPage();
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Symbol',
      'Setup',
      'Entry',
      'Current',
      'Unrealized',
      'Stop',
      'Trail',
      'Buy now?',
      'Verdict',
    ]);
  });

  it('summarises the holding + selling counts and offers a book toggle', () => {
    renderPage();
    expect(screen.getByText(/2 holding · 1 selling/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Minervini' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Manas Arora' })).toBeInTheDocument();
  });

  it('switches to the Recorded view and acknowledges a persisted decision', () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Recorded' }));
    // The table renders through the shared DataTable, which paints a desktop <table> AND a
    // md:hidden mobile card list — every cell exists twice in jsdom (CSS doesn't apply), so
    // scope to the desktop table to keep these assertions exactly-one.
    const table = screen.getByRole('table');
    // the recorded snapshot carries the run date + an unacknowledged verdict
    expect(within(table).getByText('2026-07-09')).toBeInTheDocument();
    expect(screen.getByText(/1 recorded · 1 unacknowledged/)).toBeInTheDocument();
    const ackBtn = within(table).getByRole('button', { name: 'Acknowledge' });
    fireEvent.click(ackBtn);
    expect(ackMutate).toHaveBeenCalledWith(7);
  });

  it.each([
    ['Minervini', '/signals/minervini-swing/run'],
    ['Manas Arora', '/signals/manas-arora-swing/run'],
  ])('confirms the %s run before posting its exact endpoint and no body', async (label, path) => {
    let resolveRun!: (value: unknown) => void;
    apiFetch.mockImplementationOnce(
      () => new Promise((resolve) => {
        resolveRun = resolve;
      }),
    );
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: `Run ${label} batch now` }));
    expect(
      screen.getByText(
        'Runs on CURRENT inputs — this is NOT an as-of replay of a past session. The armed 08:35 catch-up is the primary recovery path; use this only when swing_catchup_runs shows ABANDONED/refused.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/real current-input re-run every time.*second same-day call is not a no-op/i),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Confirm & run' }));
    await vi.waitFor(() => {
      expect(apiFetch).toHaveBeenCalledWith(path, { method: 'POST' });
    });
    expect(apiFetch).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Run Minervini batch now' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Run Manas Arora batch now' })).toBeDisabled();

    await act(async () => resolveRun({}));
  });

  it('does not post when the run confirmation is cancelled and restores trigger focus', async () => {
    renderPage();

    const trigger = screen.getByRole('button', { name: 'Run Manas Arora batch now' });
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(apiFetch).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(trigger).toHaveFocus());
  });

  it('blocks both family runs when IST market hours begin after confirmation opens', () => {
    // UTC instants keep this proof independent of the client machine's local time zone.
    vi.setSystemTime(new Date('2026-07-27T03:44:00Z')); // Monday 09:14 IST
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'Run Minervini batch now' }));
    vi.setSystemTime(new Date('2026-07-27T03:45:00Z')); // Monday 09:15 IST
    fireEvent.click(screen.getByRole('button', { name: 'Confirm & run' }));

    expect(apiFetch).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run Minervini batch now' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Run Manas Arora batch now' })).toBeDisabled();
    expect(screen.getAllByText('Disabled during market hours (09:15–15:30 IST)')).toHaveLength(2);
  });
});
