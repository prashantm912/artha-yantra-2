import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import { createElement } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Transport is mocked (no network); the D4 P2-2 job-annotation + saved-view hooks are pure URL/body
// wiring over apiFetch, so we assert the exact call shape each one issues.
const apiFetch = vi.fn();
vi.mock('./client.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client.ts')>();
  return { ...actual, apiFetch: (...args: unknown[]) => apiFetch(...args) };
});

import {
  useJobs,
  useAnnotateJob,
  useSavedViews,
  useCreateSavedView,
  useDeleteSavedView,
} from './backtests.ts';

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('backtests — D4 P2-2 job tags + saved views', () => {
  beforeEach(() => apiFetch.mockReset());

  it('useJobs threads the per-job tag filter as ?tag=', async () => {
    apiFetch.mockResolvedValue({ items: [] });
    const { result } = renderHook(
      () => useJobs(null, null, 0, null, null, null, null, 'alpha'),
      { wrapper: wrap() },
    );
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    const url = apiFetch.mock.calls[0][0] as string;
    expect(url).toContain('tag=alpha');
  });

  it('useJobs omits ?tag= when no job tag is set (the strategy filters stay independent)', async () => {
    apiFetch.mockResolvedValue({ items: [] });
    const { result } = renderHook(() => useJobs(null, null, 0), { wrapper: wrap() });
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    const url = apiFetch.mock.calls[0][0] as string;
    expect(url).not.toContain('tag=');
  });

  it('useAnnotateJob PATCHes .../annotations with the full {tags, note} set', async () => {
    apiFetch.mockResolvedValue({ jobId: 'j1', tags: [' b', 'c'], note: 'why' });
    const { result } = renderHook(() => useAnnotateJob(), { wrapper: wrap() });
    act(() => result.current.mutate({ jobId: 'j1', tags: ['b', 'c'], note: 'why' }));
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiFetch).toHaveBeenCalledWith('/backtests/jobs/j1/annotations', {
      method: 'PATCH',
      json: { tags: ['b', 'c'], note: 'why' },
    });
  });

  it('useSavedViews reads the kind-scoped list', async () => {
    apiFetch.mockResolvedValue({ items: [] });
    const { result } = renderHook(() => useSavedViews('backtest_jobs'), { wrapper: wrap() });
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiFetch).toHaveBeenCalledWith('/backtests/saved-views?kind=backtest_jobs');
  });

  it('useCreateSavedView POSTs the {kind, name, filter} payload', async () => {
    apiFetch.mockResolvedValue({ id: 'v1', kind: 'backtest_jobs', name: 'mine', filter: {} });
    const filter = { status: 'running', strategyId: null, jobTag: 'x', latestOnly: true, sort: null };
    const { result } = renderHook(() => useCreateSavedView(), { wrapper: wrap() });
    act(() => result.current.mutate({ kind: 'backtest_jobs', name: 'mine', filter }));
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiFetch).toHaveBeenCalledWith('/backtests/saved-views', {
      method: 'POST',
      json: { kind: 'backtest_jobs', name: 'mine', filter },
    });
  });

  it('useDeleteSavedView DELETEs the view by id', async () => {
    apiFetch.mockResolvedValue(undefined);
    const { result } = renderHook(() => useDeleteSavedView(), { wrapper: wrap() });
    act(() => result.current.mutate('v9'));
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiFetch).toHaveBeenCalledWith('/backtests/saved-views/v9', { method: 'DELETE' });
  });
});
