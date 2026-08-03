import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CompletenessReport } from '../../api/dataQuality.ts';

const { apiFetchMock } = vi.hoisted(() => ({ apiFetchMock: vi.fn() }));

vi.mock('../../api/client.ts', () => ({ apiFetch: apiFetchMock }));

import { DataQualityPage } from './DataQualityPage.tsx';

const report: CompletenessReport = {
  date: '2026-07-11',
  items: [
    {
      scope: 'chain_capture',
      symbol: 'NIFTY 50',
      expected: 75,
      present: 75,
      coveragePct: 100,
      ok: true,
      detail: null,
      computedAt: '2026-07-12T02:30:00Z',
    },
    {
      scope: 'intraday_1m',
      symbol: 'NIFTY 50',
      expected: 375,
      present: 361,
      coveragePct: 96.26,
      ok: false,
      detail: '14 one-minute bars missing',
      computedAt: '2026-07-12T02:30:00Z',
    },
    {
      scope: 'bhavcopy_eq',
      symbol: '__SUMMARY__',
      expected: 187,
      present: 180,
      coveragePct: 96.26,
      ok: false,
      detail: '7 symbols absent vs prior day',
      computedAt: '2026-07-12T02:30:00Z',
    },
    {
      scope: 'bhavcopy_eq',
      symbol: 'RELIANCE',
      expected: 1,
      present: 0,
      coveragePct: 0,
      ok: false,
      detail: 'absent vs prior day',
      computedAt: '2026-07-12T02:30:00Z',
    },
  ],
};

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <DataQualityPage />
    </QueryClientProvider>,
  );
}

describe('DataQualityPage', () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    apiFetchMock.mockResolvedValue(report);
  });

  // Budget 2026-08-03 (#1061 suite-growth rule): measured 4433ms in a full-suite run.
  it('renders all completeness scopes, coverage, failures, and the EQ summary rollup', async () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Data Quality' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Chain capture' })).toBeInTheDocument();
    for (const scope of ['Intraday 1m', 'Bhavcopy EQ']) {
      expect(screen.getByRole('heading', { name: scope })).toBeInTheDocument();
    }
    const intradayTable = screen.getByRole('table', { name: 'Intraday 1m completeness' });
    const failedRow = within(intradayTable).getByRole('row', { name: /NIFTY 50/ });
    expect(within(failedRow).getByText('96.26%')).toBeInTheDocument();
    expect(within(failedRow).getByText('FAIL')).toBeInTheDocument();
    expect(within(failedRow).getByText('14 one-minute bars missing')).toBeInTheDocument();
    expect(screen.getByText('180/187 EQ symbols')).toBeInTheDocument();
    expect(screen.queryByText('__SUMMARY__')).not.toBeInTheDocument();
  }, 15_000);

  it('requests the selected report date', async () => {
    renderPage();
    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/market/health/completeness'),
    );

    fireEvent.change(screen.getByLabelText('Report date'), {
      target: { value: '2026-07-10' },
    });

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith(
        '/market/health/completeness?date=2026-07-10',
      ),
    );
  });

  it('shows a friendly empty state when no report exists for the day', async () => {
    apiFetchMock.mockResolvedValue({ date: '2026-07-10', items: [] });
    renderPage();

    expect(await screen.findByText('No report for this day.')).toBeInTheDocument();
  });
});
