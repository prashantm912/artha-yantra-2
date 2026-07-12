import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { OiHeatmap } from '../../api/types.ts';

const grid: OiHeatmap = {
  buckets: ['09:15', '09:20'],
  strikes: ['22400', '22500', '22600'],
  ce: [
    { x: 1, y: 0, value: 200 },
    { x: 1, y: 1, value: -100 },
    { x: 1, y: 2, value: null },
  ],
  pe: [
    { x: 1, y: 0, value: -50 },
    { x: 1, y: 1, value: 300 },
    { x: 1, y: 2, value: 10 },
  ],
  maxAbs: 300,
  asOf: '2026-06-22T09:20:00+05:30',
};

// Mock the chart (jsdom lacks the ECharts canvas) + the filter bar (pulls the symbol store/fetches).
vi.mock('../../components/OiHeatmapChart.tsx', () => ({
  CallOiHeatmap: () => null,
  PutOiHeatmap: () => null,
}));
vi.mock('../../components/FilterBar.tsx', () => ({ FilterBar: () => null }));
const useOiHeatmap = vi.fn();
vi.mock('../../api/oiAnalytics.ts', () => ({ useOiHeatmap: () => useOiHeatmap() }));

import { OiHeatmapPage } from './OiHeatmapPage.tsx';

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <OiHeatmapPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('OiHeatmapPage', () => {
  it('renders both leg headings + the grid KPIs when data is present', () => {
    useOiHeatmap.mockReturnValue({ data: grid, isFetching: false, isLoading: false, refetch: () => {} });
    renderPage();
    expect(screen.getByText('Call (CE) OI Change')).toBeInTheDocument();
    expect(screen.getByText('Put (PE) OI Change')).toBeInTheDocument();
    // the Strikes/Intervals metric pills carry the grid dimensions (3 strikes, 2 buckets).
    expect(screen.getByText('Strikes').parentElement).toHaveTextContent('3');
    expect(screen.getByText('Intervals').parentElement).toHaveTextContent('2');
  });

  it('renders the empty state when no grid accrued', () => {
    useOiHeatmap.mockReturnValue({ data: null, isFetching: false, isLoading: false, refetch: () => {} });
    renderPage();
    expect(screen.getByText(/No OI-change grid/)).toBeInTheDocument();
  });
});
