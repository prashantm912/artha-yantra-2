import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ChainTable, ConnectingDots, OiHeatmap, StraddleChart } from '../../api/types.ts';

// The cockpit composes five panels driven by ONE shared FilterBar. We mock the heavy children (charts
// need an ECharts canvas; the FilterBar + signals feed pull the symbol store / WS) and the four data
// hooks, then assert: (1) the shared control bar renders once, (2) every panel heading is present, and
// (3) each panel falls through to its own empty/loading copy independently.

const chain: ChainTable = {
  underlying: 'NIFTY 50',
  expiry: '2026-06-26',
  spot: '22510',
  forward: null,
  forwardSource: null,
  riskFreeRate: null,
  pcr: '0.92',
  stale: false,
  lastCaptured: false,
  asOf: '2026-06-24T09:20:00+05:30',
  interval: '3m',
  rows: [
    { strike: '22500', ce: null, pe: null },
    { strike: '22600', ce: null, pe: null },
  ],
};

const cd: ConnectingDots = {
  underlying: 'NIFTY 50',
  interval: '3m',
  asOf: '2026-06-24T09:20:00+05:30',
  rows: [
    { timeInterval: '09:20', trend: 2, dow: 1, vix: 1, volume: 1, activeStrikeIv: 1, activeStrikeOi: 1, futOi: 1, vwap: 1, supertrend: 1, rsi: 1, futPrice: 1, dailyTrend: 1 },
  ],
};

const straddle: StraddleChart = {
  underlying: 'NIFTY 50',
  expiry: '2026-06-26',
  callStrike: '22500',
  putStrike: '22500',
  interval: '3m',
  underlyingLtp: '22510',
  underlyingDayOpen: '22480',
  asOf: '2026-06-24T09:20:00+05:30',
  items: [
    { time: '09:20', open: '180', high: '185', low: '178', close: '183', ceClose: '92', peClose: '91', volume: 1200 },
  ],
};

const heat: OiHeatmap = {
  buckets: ['09:15', '09:20'],
  strikes: ['22400', '22500'],
  ce: [{ x: 1, y: 0, value: 200 }],
  pe: [{ x: 1, y: 0, value: -50 }],
  maxAbs: 200,
  asOf: '2026-06-24T09:20:00+05:30',
};

// Mock the heavy children: charts (no canvas), the FilterBar (pulls the symbol store), and the signals
// feed (REST snapshot + STOMP live).
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => <div data-testid="filter-bar" /> }));
vi.mock('../../components/StraddleChart.tsx', () => ({ StraddleChart: () => <div data-testid="straddle-chart" /> }));
vi.mock('../../components/OiHeatmapChart.tsx', () => ({
  CallOiHeatmap: () => <div data-testid="ce-heatmap" />,
  PutOiHeatmap: () => <div data-testid="pe-heatmap" />,
}));
vi.mock('../signals/SignalsFeedPanel.tsx', () => ({
  SignalsFeedPanel: () => <div data-testid="signals-feed" />,
}));
vi.mock('./PaperBookPanel.tsx', () => ({
  PaperBookPanel: () => <div data-testid="paper-book" />,
}));

const useChainTable = vi.fn();
const useConnectingDots = vi.fn();
const useStraddleChart = vi.fn();
const useOiHeatmap = vi.fn();
vi.mock('../../api/oiAnalytics.ts', () => ({
  useChainTable: () => useChainTable(),
  useConnectingDots: () => useConnectingDots(),
  useStraddleChart: () => useStraddleChart(),
  useOiHeatmap: () => useOiHeatmap(),
}));

import { CockpitPage } from './CockpitPage.tsx';

const idle = { isFetching: false, isLoading: false, refetch: vi.fn() };

/**
 * The Option-chain panel's own <section>. Freshness assertions MUST be scoped to it: the page
 * header carries its own LiveDot, so a bare getByText('Live') matches two nodes and would also
 * pass if the chip landed on the wrong panel.
 */
function chainPanel(): HTMLElement {
  const section = screen.getByRole('heading', { name: 'Option chain' }).closest('section');
  if (section == null) throw new Error('Option chain panel <section> not found');
  return section as HTMLElement;
}

function renderCockpit() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <CockpitPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('CockpitPage', () => {
  // Budget 2026-08-03 (#1061 suite-growth rule): measured 5104ms in a full-suite run.
  it('renders the single shared FilterBar and all five panel headings when data is present', () => {
    useChainTable.mockReturnValue({ data: chain, ...idle });
    useConnectingDots.mockReturnValue({ data: cd, ...idle });
    useStraddleChart.mockReturnValue({ data: straddle, ...idle });
    useOiHeatmap.mockReturnValue({ data: heat, ...idle });
    renderCockpit();

    // Exactly one shared control bar drives every panel.
    expect(screen.getAllByTestId('filter-bar')).toHaveLength(1);

    // Each composed panel heading is present.
    expect(screen.getByRole('heading', { name: 'Option chain' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'OI confluence matrix' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Straddle premium' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Live signals' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Paper book' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'OI change heatmap' })).toBeInTheDocument();

    // The signals feed + paper-book panels are composed; the header derives the sentiment from the matrix.
    expect(screen.getByTestId('signals-feed')).toBeInTheDocument();
    expect(screen.getByTestId('paper-book')).toBeInTheDocument();
    // Trend code 2 → "Bullish" — the header sentiment strip carries the derived badge.
    expect(screen.getByText('Sentiment', { exact: false })).toHaveTextContent('Bullish');

    // The chart panels render (mocked — straddle + both heatmap legs).
    expect(screen.getByTestId('straddle-chart')).toBeInTheDocument();
    expect(screen.getByTestId('ce-heatmap')).toBeInTheDocument();
    expect(screen.getByTestId('pe-heatmap')).toBeInTheDocument();
  }, 15_000);

  it('badges the Option-chain panel as stale, with the capture DATE, when the chain degraded to the last captured book', () => {
    // After market close the server serves the last CAPTURED chain instead of 503-ing (freshness
    // complete=false + the capture asOf). Those rows render in the SAME table as live ones, so
    // without this chip the panel would show yesterday's book as though it were live — worse than
    // the 503 it replaced. The date must be visible, not just HH:MM: a chain from three weeks ago
    // and yesterday's close would otherwise be identical on screen.
    useChainTable.mockReturnValue({
      data: {
        ...chain,
        lastCaptured: true,
        asOf: '2026-06-23T15:30:00+05:30',
        freshness: {
          asOf: '2026-06-23T15:30:00+05:30',
          source: 'capture',
          historyStart: null,
          staleSeconds: 63_000,
          complete: false,
          provenance: 'live',
        },
      },
      ...idle,
    });
    useConnectingDots.mockReturnValue({ data: cd, ...idle });
    useStraddleChart.mockReturnValue({ data: straddle, ...idle });
    useOiHeatmap.mockReturnValue({ data: heat, ...idle });
    renderCockpit();

    const panel = chainPanel();
    expect(within(panel).getByText('Stale')).toBeInTheDocument();
    expect(within(panel).getByText('as of 2026-06-23 15:30')).toBeInTheDocument();
  });

  it('leaves the Option-chain panel badged Live when the chain is a real live computation', () => {
    useChainTable.mockReturnValue({
      data: {
        ...chain,
        freshness: {
          asOf: new Date().toISOString(),
          source: 'capture',
          historyStart: null,
          staleSeconds: 5,
          complete: true,
          provenance: 'live',
        },
      },
      ...idle,
    });
    useConnectingDots.mockReturnValue({ data: cd, ...idle });
    useStraddleChart.mockReturnValue({ data: straddle, ...idle });
    useOiHeatmap.mockReturnValue({ data: heat, ...idle });
    renderCockpit();

    const panel = chainPanel();
    expect(within(panel).getByText('Live')).toBeInTheDocument();
    expect(within(panel).queryByText('Stale')).not.toBeInTheDocument();
  });

  it('renders each panel’s empty state independently when its data is absent', () => {
    useChainTable.mockReturnValue({ data: null, ...idle });
    useConnectingDots.mockReturnValue({ data: null, ...idle });
    useStraddleChart.mockReturnValue({ data: null, ...idle });
    useOiHeatmap.mockReturnValue({ data: null, ...idle });
    renderCockpit();

    expect(screen.getByText(/No chain —/)).toBeInTheDocument();
    expect(screen.getByText(/No matrix —/)).toBeInTheDocument();
    expect(screen.getByText(/No straddle candles —/)).toBeInTheDocument();
    expect(screen.getByText(/No OI-change grid —/)).toBeInTheDocument();
  });

  it('shows loading copy on the chart panels while their queries are in flight', () => {
    useChainTable.mockReturnValue({ data: null, ...idle, isLoading: true });
    useConnectingDots.mockReturnValue({ data: null, ...idle, isLoading: true });
    useStraddleChart.mockReturnValue({ data: null, ...idle, isLoading: true });
    useOiHeatmap.mockReturnValue({ data: null, ...idle, isLoading: true });
    renderCockpit();

    // The straddle panel renders its in-flight "Loading…" copy (not the empty state).
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    // The chain/matrix/heatmap panels suppress their empty copy while loading.
    expect(screen.queryByText(/No chain —/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No OI-change grid —/)).not.toBeInTheDocument();
  });
});
