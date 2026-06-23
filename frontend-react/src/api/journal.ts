// Trade-journal cockpit data layer (master plan §20 parity, F-44A). Ports JournalStore: the
// weekly-review list (filter by tag / linked-entity) + create / delete over /api/v1/journal. Free
// entries (no link) are first-class.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

export interface JournalLink {
  signalId?: number;
  paperPositionId?: number;
  backtestRunId?: string;
  backtestTradeId?: number;
}

export interface JournalEntry extends JournalLink {
  id: number;
  note: string;
  tags: string[];
  disciplineRating: number | null;
  emotionRating: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface NewJournalEntry extends JournalLink {
  note: string;
  tags: string[];
  disciplineRating?: number | null;
  emotionRating?: number | null;
}

export const LINKED_OPTIONS = [
  { value: 'signal', label: 'Signals' },
  { value: 'paper', label: 'Paper' },
  { value: 'backtest', label: 'Backtests' },
  { value: 'none', label: 'Free entries' },
];

const KEY = 'journal';

export function useJournal(tag: string | null, linkedTo: string | null) {
  return useQuery({
    queryKey: [KEY, tag, linkedTo],
    queryFn: () => {
      const params = new URLSearchParams({ limit: '200' });
      if (tag) params.set('tag', tag);
      if (linkedTo) params.set('linkedTo', linkedTo);
      return apiFetch<{ items: JournalEntry[] }>(`/journal?${params.toString()}`);
    },
  });
}

export function useCreateJournal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: NewJournalEntry) => apiFetch<JournalEntry>('/journal', { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
}

export function useDeleteJournal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiFetch<void>(`/journal/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: [KEY] }),
  });
}

export function linkLabel(e: JournalEntry): string {
  if (e.signalId) return `signal #${e.signalId}`;
  if (e.paperPositionId) return `paper #${e.paperPositionId}`;
  if (e.backtestRunId || e.backtestTradeId) {
    return `backtest ${e.backtestRunId?.slice(0, 8) ?? ''}${e.backtestTradeId ? ' #' + e.backtestTradeId : ''}`;
  }
  return 'free';
}
