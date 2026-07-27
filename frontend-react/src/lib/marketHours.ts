/**
 * True inside the IST cash session: Mon–Fri 09:15–15:30. Holidays are NOT consulted — the only
 * available frontend calendar is date-only, so safety gates conservatively treat a weekday holiday's
 * session window as closed to manual actions while refresh gates may perform a harmless spare refetch.
 *
 * Implemented with Intl.formatToParts, not `new Date(toLocaleString(...))`: the latter re-PARSES a
 * locale string, and now that this function backs a safety gate (the manual swing-run block), a parse
 * failure would read as NaN -> "market closed" -> gate OPEN — a safety check must not fail open
 * (cross-vendor review m6). If Intl itself throws, we fail CLOSED (treat as market hours).
 */
const IST_PARTS = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Kolkata',
  weekday: 'short',
  hour: 'numeric',
  minute: 'numeric',
  hour12: false,
});

export function isMarketHoursIst(now: Date = new Date()): boolean {
  let weekday = '';
  let hour = Number.NaN;
  let minute = Number.NaN;
  try {
    for (const part of IST_PARTS.formatToParts(now)) {
      if (part.type === 'weekday') weekday = part.value;
      else if (part.type === 'hour') hour = Number(part.value);
      else if (part.type === 'minute') minute = Number(part.value);
    }
  } catch {
    return true; // fail CLOSED: an unknown clock must block the manual run, never allow it
  }
  if (weekday === 'Sat' || weekday === 'Sun') return false;
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return true; // fail CLOSED
  const mins = (hour === 24 ? 0 : hour) * 60 + minute;
  return mins >= 9 * 60 + 15 && mins <= 15 * 60 + 30;
}
