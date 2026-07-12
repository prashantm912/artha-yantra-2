import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import { renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// Transport is mocked (no network); ApiError stays real so the 404-branch `instanceof` check holds.
const apiFetch = vi.fn();
vi.mock('./client.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./client.ts')>();
  return { ...actual, apiFetch: (...args: unknown[]) => apiFetch(...args) };
});

import { ApiError } from './client.ts';
import { usePremiumSeries, useChainHistory } from './oiAnalytics.ts';
import { useSymbolContext } from '../stores/symbolContext.store.ts';

function wrap() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
}

describe('oiAnalytics — Phase 3B hooks', () => {
  beforeEach(() => {
    apiFetch.mockReset();
    useSymbolContext.setState({
      name: 'NIFTY 50',
      expiry: '2026-07-15',
      interval: '3m',
      mode: 'live',
      date: null,
    });
  });

  it('usePremiumSeries reads the /premium-series feed', async () => {
    apiFetch.mockResolvedValue({ items: [], asOf: null });
    const { result } = renderHook(() => usePremiumSeries(), { wrapper: wrap() });
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiFetch).toHaveBeenCalledWith(
      expect.stringContaining('/market/options/premium-series'),
    );
  });

  it('useChainHistory reads the stored snapshot on a hit', async () => {
    apiFetch.mockResolvedValue({
      underlying: 'NIFTY 50',
      expiry: '2026-07-15',
      ts: '2026-07-10T10:30:00+05:30',
      rows: [],
    });
    const { result } = renderHook(
      () => useChainHistory('2026-07-10T10:30:00+05:30', true),
      { wrapper: wrap() },
    );
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiFetch).toHaveBeenCalledWith(
      expect.stringContaining('/market/options/chain/history?'),
    );
    expect(result.current.data?.ts).toBe('2026-07-10T10:30:00+05:30');
  });

  it('useChainHistory maps a 404 to null (honest pre-capture empty state)', async () => {
    apiFetch.mockRejectedValue(new ApiError(404, 'NOT_FOUND', 'no stored snapshot'));
    const { result } = renderHook(
      () => useChainHistory('2026-07-10T10:30:00+05:30', true),
      { wrapper: wrap() },
    );
    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it('useChainHistory stays disabled until the panel is open with an `at`', () => {
    const { result } = renderHook(() => useChainHistory(null, false), { wrapper: wrap() });
    expect(result.current.fetchStatus).toBe('idle');
    expect(apiFetch).not.toHaveBeenCalled();
  });
});
