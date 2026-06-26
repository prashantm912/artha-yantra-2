import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client.ts';

// Calendar status (GET /market/status — the bundled NSE trading calendar, NOT the Upstox session-phase
// /market/market-status used by Pre-Open). Drives the app-wide "default to the last trading session"
// behaviour so weekend/holiday pages don't land on an empty day and render flat/blank.

export interface CalendarStatus {
  serverTime: string;
  tradingDay: boolean;
  marketOpen: boolean;
  nextTradingDay: string;
  previousTradingDay: string;
  /** Today if it's a trading day, else the most recent prior session ('' on an uncovered year). */
  lastTradingDay: string;
}

/** The IST calendar day as 'YYYY-MM-DD' — the fallback until /status resolves. */
function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date());
}

export function useCalendarStatus() {
  return useQuery({
    queryKey: ['market', 'calendar-status'],
    queryFn: () => apiFetch<CalendarStatus>('/market/status', { silenceToast: true }),
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * The sensible default date for history/intraday pages: the last trading session (today when it's a
 * trading day, else the previous one). Falls back to the IST calendar day until /status resolves, so a
 * page can initialise its date synchronously and self-correct once the calendar loads.
 */
export function useDefaultDate(): string {
  const last = useCalendarStatus().data?.lastTradingDay;
  return last && last.length === 10 ? last : todayIst();
}
