import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Insight } from '../../api/insights.ts';

// Spy on navigation (the prefill flow routes to the EXISTING /orders + /journal UI).
const navigate = vi.fn();
vi.mock('react-router-dom', async (orig) => {
  const actual = await orig<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigate };
});

// Partial-mock the client so ApiError stays REAL (the component's 422 surfacing checks `instanceof`).
const apiFetch = vi.fn();
vi.mock('../../api/client.ts', async (orig) => {
  const actual = await orig<typeof import('../../api/client.ts')>();
  return { ...actual, apiFetch: (path: string, opts?: unknown) => apiFetch(path, opts) };
});

import { ApiError } from '../../api/client.ts';
import { InsightActions } from './InsightActions.tsx';

const SIGNAL: Insight = {
  id: 'sig-insight',
  generatedAt: '2026-07-11T09:47:12+05:30',
  type: 'SIGNAL_PRIORITY',
  severity: 'WARN',
  scope: 'signal:42',
  title: 'NIFTY 25200 CE scalp',
  explanation: 'e',
  dataTrust: 'OK',
  suppressed: false,
  status: 'OPEN',
};

interface ActOpts {
  json?: { action?: string };
}

function actResponse(action: string): unknown {
  switch (action) {
    case 'MUTE_TYPE':
      return { insightId: SIGNAL.id, action, status: 'DONE', note: 'muted' };
    case 'OPEN_TICKET':
      return {
        insightId: SIGNAL.id,
        action,
        status: 'PREFILL',
        ticket: { signalId: 42, exchange: 'NFO', tradingsymbol: 'NIFTY25200CE', side: 'BUY', qty: 75, stopLoss: 40, target: 90 },
      };
    case 'JOURNAL_DRAFT_ACCEPT':
      return { insightId: SIGNAL.id, action, status: 'PREFILL', journal: { signalId: 42, note: 'Draft from insight' } };
    case 'ACK_SELL_DECISION':
      return { insightId: SIGNAL.id, action, status: 'PROPOSED', sellDecisionId: 7 };
    default:
      return { insightId: SIGNAL.id, action, status: 'DONE' };
  }
}

beforeEach(() => {
  apiFetch.mockReset();
  navigate.mockReset();
  apiFetch.mockImplementation((path: string, opts?: ActOpts) => {
    if (path.includes('/act')) return Promise.resolve(actResponse(opts?.json?.action ?? ''));
    if (path.includes('/ack')) return Promise.resolve({});
    return Promise.resolve({});
  });
});

function renderActions(insight: Insight, only?: Parameters<typeof InsightActions>[0]['only']) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const onActed = vi.fn();
  render(
    <QueryClientProvider client={qc}>
      <InsightActions insight={insight} only={only} onActed={onActed} />
    </QueryClientProvider>,
  );
  return { onActed };
}

describe('InsightActions', () => {
  it('mutes a type via /act (DONE, in-module — no navigation)', async () => {
    const { onActed } = renderActions(SIGNAL);
    await userEvent.click(screen.getByRole('button', { name: 'Mute type' }));
    expect(
      apiFetch.mock.calls.some(
        ([p, o]) =>
          p === `/insights/${SIGNAL.id}/act` && (o as ActOpts)?.json?.action === 'MUTE_TYPE',
      ),
    ).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    expect(onActed).toHaveBeenCalled();
  });

  it('routes an OPEN_TICKET PREFILL to the existing /orders paper ticket', async () => {
    renderActions(SIGNAL);
    await userEvent.click(screen.getByRole('button', { name: 'Take → ticket' }));
    const dest = navigate.mock.calls.at(-1)?.[0] as string | undefined;
    expect(dest).toBeTruthy();
    expect(dest).toContain('/orders?');
    expect(dest).toContain('sig=42');
    expect(dest).toContain('symbol=NFO%3ANIFTY25200CE');
    expect(dest).toContain('qty=75');
  });

  it('routes a JOURNAL_DRAFT_ACCEPT PREFILL to the existing /journal form', async () => {
    renderActions(SIGNAL);
    await userEvent.click(screen.getByRole('button', { name: 'Draft journal' }));
    const dest = navigate.mock.calls.at(-1)?.[0] as string | undefined;
    expect(dest).toContain('/journal?');
    expect(dest).toContain('draftSignalId=42');
  });

  it('BLOCKED data trust gates every action — aria-disabled (still focusable) with the reason described, click no-ops', async () => {
    renderActions({ ...SIGNAL, dataTrust: 'BLOCKED' });
    const take = screen.getByRole('button', { name: 'Take → ticket' });
    const mute = screen.getByRole('button', { name: 'Mute type' });
    // aria-disabled, NOT natively disabled — the button stays in the tab order / a11y tree.
    expect(take).toHaveAttribute('aria-disabled', 'true');
    expect(mute).toHaveAttribute('aria-disabled', 'true');
    expect(take).not.toBeDisabled();
    expect(mute).not.toBeDisabled();
    // The WHY is programmatically associated via aria-describedby.
    expect(take).toHaveAccessibleDescription(/BLOCKED/);
    expect(mute).toHaveAccessibleDescription(/BLOCKED/);
    // Activating a gated button is a no-op — /act is never called, nothing navigates.
    await userEvent.click(take);
    expect(apiFetch).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('DEGRADED data trust gates only the order prefill, not journal/mute', () => {
    renderActions({ ...SIGNAL, dataTrust: 'DEGRADED' });
    const take = screen.getByRole('button', { name: 'Take → ticket' });
    expect(take).toHaveAttribute('aria-disabled', 'true');
    expect(take).toHaveAccessibleDescription(/DEGRADED/);
    expect(screen.getByRole('button', { name: 'Draft journal' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByRole('button', { name: 'Draft journal' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Mute type' })).not.toHaveAttribute('aria-disabled');
    expect(screen.getByRole('button', { name: 'Mute type' })).toBeEnabled();
  });

  it('surfaces a raced 422 trust-gate reason inline (never swallowed)', async () => {
    apiFetch.mockImplementationOnce(() =>
      Promise.reject(new ApiError(422, 'DATA_STALE', 'insight data_trust is BLOCKED — advice cannot outrun its data', undefined, true)),
    );
    renderActions(SIGNAL);
    await userEvent.click(screen.getByRole('button', { name: 'Take → ticket' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/data_trust is BLOCKED/);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('only=[OPEN_TICKET] renders just the Take button (Focus row)', () => {
    renderActions(SIGNAL, ['OPEN_TICKET']);
    expect(screen.getByRole('button', { name: 'Take → ticket' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mute type' })).not.toBeInTheDocument();
  });
});
