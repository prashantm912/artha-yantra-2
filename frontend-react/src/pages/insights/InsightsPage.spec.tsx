import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Insight } from '../../api/insights.ts';

// Drive the page through the real hooks against a mocked apiFetch (the JobsPage.spec pattern, one layer
// lower) so the query/mutation wiring + the shared explain drawer are exercised end-to-end.
const apiFetch = vi.fn();
vi.mock('../../api/client.ts', () => ({
  apiFetch: (path: string, opts?: unknown) => apiFetch(path, opts),
}));

import { InsightsPage } from './InsightsPage.tsx';

const SIGNAL: Insight = {
  id: '11111111-1111-1111-1111-111111111111',
  generatedAt: '2026-07-11T09:47:12+05:30',
  type: 'SIGNAL_PRIORITY',
  severity: 'WARN',
  scope: 'signal:42',
  title: 'NIFTY 25200 CE scalp — priority 82',
  explanation: 'Composite 0.81 vs threshold 0.72; PCR flipped bullish since open.',
  evidence: [
    {
      label: 'composite vs threshold',
      value: '0.81 vs 0.72',
      source: { endpoint: '/api/v1/signals/42', asOf: '2026-07-11T09:47:12+05:30' },
    },
    { label: 'PCR shift', value: '0.87→1.14 since open' },
  ],
  priority: 82.75,
  priorityDetail: {
    score: 82.75,
    band: 'A',
    trustCap: 1.0,
    components: [{ key: 'edge', weight: 0.3, c: 0.9, points: 27.0, evidence: [{ label: 'composite vs threshold', value: '0.81 vs 0.72' }] }],
  },
  dataTrust: 'OK',
  trustReasons: [],
  suppressed: false,
  status: 'OPEN',
};

const DATA_TRUST: Insight = {
  id: '22222222-2222-2222-2222-222222222222',
  generatedAt: '2026-07-11T08:00:00+05:30',
  type: 'DATA_TRUST',
  severity: 'WARN',
  scope: 'dataops',
  title: 'Participant-OI missing for 2026-07-09',
  explanation: 'FII long-short is 1 session stale.',
  evidence: [{ label: 'ingest run', value: 'participant-oi FAILED' }],
  priority: null,
  priorityDetail: null,
  dataTrust: 'DEGRADED',
  trustReasons: ['participant-oi ingest hole 2026-07-09'],
  suppressed: false,
  status: 'OPEN',
};

function route(path: string): Promise<unknown> {
  if (path.includes('/ack')) return Promise.resolve({ id: '1', status: 'ACKED' });
  if (path.includes('/dismiss')) return Promise.resolve({ id: '1', status: 'DISMISSED' });
  if (path.includes('/feedback')) return Promise.resolve({ id: '1', status: 'OPEN' });
  if (path.startsWith('/insights?')) return Promise.resolve({ items: [SIGNAL, DATA_TRUST], limit: 100, offset: 0 });
  return Promise.resolve({ items: [] });
}

beforeEach(() => {
  apiFetch.mockReset();
  apiFetch.mockImplementation((path: string) => route(path));
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <InsightsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('InsightsPage', () => {
  it('lists insights and opens the shared explain drawer with the priority breakdown + evidence', async () => {
    renderPage();
    await screen.findByRole('button', { name: /NIFTY 25200 CE scalp/ });
    expect(screen.getByText('Participant-OI missing for 2026-07-09')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /NIFTY 25200 CE scalp/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Composite 0.81 vs threshold 0.72/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Priority 82.75 \(A\)/)).toBeInTheDocument();
    expect(within(dialog).getByText('edge')).toBeInTheDocument();
    expect(within(dialog).getAllByText(/composite vs threshold/).length).toBeGreaterThan(0);
  });

  it('re-queries with the server severity filter', async () => {
    renderPage();
    await screen.findByRole('button', { name: /NIFTY 25200 CE scalp/ });
    await userEvent.selectOptions(screen.getByLabelText('Filter by severity'), 'WARN');
    expect(apiFetch.mock.calls.some(([p]) => typeof p === 'string' && p.includes('severity=WARN'))).toBe(true);
  });

  it('free-text search pre-filters the fetched page client-side', async () => {
    renderPage();
    await screen.findByRole('button', { name: /NIFTY 25200 CE scalp/ });
    await userEvent.type(screen.getByLabelText('Search insights'), 'Participant');
    expect(screen.queryByRole('button', { name: /NIFTY 25200 CE scalp/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Participant-OI missing/ })).toBeInTheDocument();
  });

  it('bulk-acks the selected rows via the ack endpoint', async () => {
    renderPage();
    await screen.findByRole('button', { name: /NIFTY 25200 CE scalp/ });
    await userEvent.click(screen.getByLabelText('Select insight: NIFTY 25200 CE scalp — priority 82'));
    await userEvent.click(screen.getByRole('button', { name: 'Ack selected' }));
    expect(
      apiFetch.mock.calls.some(
        ([p]) => typeof p === 'string' && p === '/insights/11111111-1111-1111-1111-111111111111/ack',
      ),
    ).toBe(true);
  });
});
