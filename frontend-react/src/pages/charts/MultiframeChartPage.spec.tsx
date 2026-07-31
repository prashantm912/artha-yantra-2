import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../components/charts/AdvanceChart.tsx', () => ({
  AdvanceChart: () => <div data-testid="advancechart" />,
}));

// The panes' candle reads are recorded rather than executed. The interval defect is a REQUEST for an
// interval the API does not serve, so the assertion has to see the call args — the rendered <select>
// cannot show it, because a <select> whose value is absent from its options silently displays the first
// option instead (that is exactly why the live pane read "1m" while fetching "30m").
// Hoisted so the payload keeps a STABLE reference across renders (a fresh object per render would
// re-fire the consumers' data-dependent effects → re-render loop, per ChartsPage.spec.tsx).
const { calls, CANDLES } = vi.hoisted(() => ({
  calls: [] as { symbol: string; interval: string }[],
  CANDLES: {
    items: [
      {
        exchange: 'NSE',
        tradingsymbol: 'NIFTY 50',
        interval: '15m',
        bucket: '2026-07-31T09:15:00+05:30',
        open: '100',
        high: '110',
        low: '95',
        close: '105',
        volume: 1000,
        oi: null,
        source: 'LIVE',
      },
    ],
  },
}));

vi.mock('../../api/charts.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/charts.ts')>();
  return {
    ...actual, // keep CHART_INTERVALS — the served-interval set under test
    useCandles: (symbol: string, interval: string) => {
      calls.push({ symbol, interval });
      return { data: CANDLES, isLoading: false };
    },
  };
});

import { CHART_INTERVALS } from '../../api/charts.ts';
import { MultiframeChartPage } from './MultiframeChartPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/multiframe-chart']}>
        <MultiframeChartPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** The symbols the four panes asked for on their most recent render, in pane order. */
function lastRound() {
  return calls.slice(-4).map((c) => c.symbol);
}

describe('MultiframeChartPage', () => {
  beforeEach(() => {
    calls.length = 0;
  });

  // task_e2e01b: the index default was ['15m','5m','3m','30m'] but the API serves no 30m, so pane 4
  // 400'd on every default load. Any default outside CHART_INTERVALS is the same defect.
  it('never asks for an interval the candles API does not serve — index symbol default', () => {
    renderPage();

    expect(calls.length).toBeGreaterThan(0);
    // the default symbol is an index, i.e. the panes are on the path that used to swap in '30m'
    expect(lastRound()).toEqual(Array(4).fill('NSE:NIFTY 50'));

    const served: readonly string[] = CHART_INTERVALS;
    const unsupported = [...new Set(calls.map((c) => c.interval))].filter(
      (iv) => !served.includes(iv),
    );
    expect(unsupported).toEqual([]);
  });

  // FG-03
  it('gives each pane its own symbol — loading one pane leaves the other three alone', () => {
    renderPage();
    expect(
      (screen.getByLabelText('Instrument for pane 2') as HTMLInputElement).value,
    ).toBe('NSE:NIFTY 50');

    fireEvent.change(screen.getByLabelText('Instrument for pane 2'), {
      target: { value: 'NSE:TCS' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Load pane 2' }));

    expect(lastRound()).toEqual(['NSE:NIFTY 50', 'NSE:TCS', 'NSE:NIFTY 50', 'NSE:NIFTY 50']);
    expect(
      (screen.getByLabelText('Instrument for pane 1') as HTMLInputElement).value,
    ).toBe('NSE:NIFTY 50');
    expect((screen.getByLabelText('Instrument for pane 2') as HTMLInputElement).value).toBe(
      'NSE:TCS',
    );
  });

  it('keeps the toolbar idiom — one instrument loads into all four panes', () => {
    renderPage();
    fireEvent.change(screen.getByLabelText('Instrument for pane 3'), {
      target: { value: 'NSE:TCS' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Load pane 3' }));
    expect(lastRound()[2]).toBe('NSE:TCS');

    fireEvent.change(screen.getByLabelText('Instrument'), { target: { value: 'NSE:INFY' } });
    fireEvent.click(screen.getByRole('button', { name: 'Load' }));

    expect(lastRound()).toEqual(Array(4).fill('NSE:INFY'));
    // the per-pane inputs re-sync, so pane 3 no longer shows the symbol it was individually loaded with
    expect((screen.getByLabelText('Instrument for pane 3') as HTMLInputElement).value).toBe(
      'NSE:INFY',
    );
  });

  it('keeps each pane its own interval selector', () => {
    renderPage();
    expect((screen.getByLabelText('Interval for pane 1') as HTMLSelectElement).value).toBe('15m');
    fireEvent.change(screen.getByLabelText('Interval for pane 1'), { target: { value: '1h' } });

    expect(calls.slice(-4).map((c) => c.interval)).toEqual(['1h', '5m', '3m', '1m']);
  });
});
