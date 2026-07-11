import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Insight } from '../../api/insights.ts';

const apiFetch = vi.fn();
vi.mock('../../api/client.ts', () => ({
  apiFetch: (path: string, opts?: unknown) => apiFetch(path, opts),
}));

import { FocusPanel } from './FocusPanel.tsx';

const SIGNAL: Insight = {
  id: 'aaaa',
  generatedAt: '2026-07-11T09:47:12+05:30',
  type: 'SIGNAL_PRIORITY',
  severity: 'WARN',
  scope: 'signal:42',
  title: 'NIFTY 25200 CE scalp',
  explanation: 'Composite 0.81 vs threshold 0.72.',
  evidence: [{ label: 'PCR shift', value: '0.87→1.14 since open' }],
  priority: 82.75,
  priorityDetail: { score: 82.75, band: 'A', trustCap: 1, components: [] },
  dataTrust: 'OK',
  suppressed: false,
  status: 'OPEN',
};
const ATTENTION: Insight = {
  id: 'bbbb',
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
  trustReasons: ['ingest hole'],
  suppressed: false,
  status: 'OPEN',
};

function route(path: string): Promise<unknown> {
  if (path.includes('/ack')) return Promise.resolve({ id: 'aaaa', status: 'ACKED' });
  if (path.startsWith('/insights/focus')) {
    return Promise.resolve({ signalQueue: [SIGNAL], attentionQueue: [ATTENTION], suppressed: 3 });
  }
  if (path.startsWith('/insights/summary')) {
    return Promise.resolve({ bySeverity: [{ key: 'WARN', count: 2 }], byStatus: [{ key: 'OPEN', count: 2 }], suppressed: 3 });
  }
  return Promise.resolve({});
}

beforeEach(() => {
  apiFetch.mockReset();
  apiFetch.mockImplementation((path: string) => route(path));
});

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <FocusPanel />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('FocusPanel', () => {
  it('renders the signal + attention queues and a feed link', async () => {
    renderPanel();
    expect(await screen.findByRole('button', { name: 'NIFTY 25200 CE scalp' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Participant-OI missing for 2026-07-09' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Open feed/ })).toHaveAttribute('href', '/insights');
  });

  it('opens the explain drawer from Review', async () => {
    renderPanel();
    await screen.findByRole('button', { name: 'NIFTY 25200 CE scalp' });
    const reviewButtons = screen.getAllByRole('button', { name: 'Review' });
    await userEvent.click(reviewButtons[0]);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Composite 0.81 vs threshold 0.72/)).toBeInTheDocument();
  });

  it('acks a queue item via the ack endpoint', async () => {
    renderPanel();
    await screen.findByRole('button', { name: 'NIFTY 25200 CE scalp' });
    const ackButtons = screen.getAllByRole('button', { name: 'Ack' });
    await userEvent.click(ackButtons[0]);
    expect(apiFetch.mock.calls.some(([p]) => typeof p === 'string' && p === '/insights/aaaa/ack')).toBe(true);
  });
});
