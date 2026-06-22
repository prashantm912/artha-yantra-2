import { useQuery } from '@tanstack/react-query';
import { apiFetch, listItems } from './client.ts';
import type { HolidayRow } from './types.ts';

// Market Holidays feed (§features/market-holidays — oipulse "Market Holidays"). A static reference list:
// the NSE trading holidays the bundled market-calendar covers, date-ascending, each with its weekday +
// published description. The Passed/Coming validity is derived on the page (vs today), not on the wire.

/** The NSE trading-holiday reference list. No params; cached for the session. */
export function useHolidays() {
  return useQuery({
    queryKey: ['market', 'holidays'],
    queryFn: async () => {
      const res = await apiFetch<{ items?: HolidayRow[] }>('/market/holidays', { silenceToast: true });
      return listItems(res);
    },
    staleTime: 60 * 60 * 1000, // the calendar is static for the day
  });
}
