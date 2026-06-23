// Settings cockpit data layer (master plan §20 parity, E-8): Kite Connect status + OAuth login-url,
// and the instrument data-sync trigger + status. Theme lives in the session store. The OAuth popup is
// opened by the user's click (the user authenticates in the broker window — never automated).

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface KiteStatus {
  connected: boolean;
  state?: string;
  kiteUserId?: string;
  tokenValidUntil?: string;
  tickerState?: string;
}

export interface SyncStatus {
  jobId?: string;
  state?: string;
  lastRun?: string;
  durationMs?: number;
  error?: string;
}

export function useKiteStatus() {
  return useQuery({
    queryKey: ['kite', 'status'],
    queryFn: () => apiFetch<KiteStatus>('/auth/kite/status'),
  });
}

export function useSyncStatus() {
  return useQuery({
    queryKey: ['instruments', 'sync-status'],
    queryFn: () => apiFetch<SyncStatus>('/instruments/sync/status'),
  });
}

/** Fetch the Kite OAuth login URL (the page opens it in a popup on the user's click). */
export async function fetchKiteLoginUrl(): Promise<string | null> {
  const r = await apiFetch<{ url: string }>('/auth/kite/login-url');
  return r.url ?? null;
}

/** Trigger an instrument sync (async — the run's outcome is polled via the sync-status query). */
export function useSyncInstruments() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch('/instruments/sync', { method: 'POST', json: {} }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['instruments', 'sync-status'] }),
  });
}
