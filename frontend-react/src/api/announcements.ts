import { useQuery } from '@tanstack/react-query';
import { apiFetch, listItems } from './client.ts';
import type { Announcement } from './types.ts';

// Announcement feed (§equity/announcement) — NSE corporate filings over a date range, optionally
// narrowed to one symbol. The {items} envelope is empty when the NSE source is not live (mock /
// analytics off) or the call fails — the page renders its empty state. Keyed on the COMMITTED filter
// (from/to/symbol) so the NSE call fires on Go, not on every keystroke; filings are slow-moving so the
// result is cached for a few minutes.

export interface AnnouncementQuery {
  from: string;
  to: string;
  symbol: string;
}

/** Announcements for the committed range + optional symbol (newest first). */
export function useAnnouncements(q: AnnouncementQuery) {
  return useQuery({
    queryKey: ['market', 'announcements', q.from, q.to, q.symbol],
    queryFn: async () => {
      const params = new URLSearchParams({ from: q.from, to: q.to });
      if (q.symbol.trim()) params.set('symbol', q.symbol.trim());
      const res = await apiFetch<{ items?: Announcement[] }>(
        `/market/equity/announcements?${params.toString()}`,
        { silenceToast: true },
      );
      return listItems(res);
    },
    staleTime: 5 * 60 * 1000,
  });
}
