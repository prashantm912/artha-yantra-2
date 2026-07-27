/**
 * True inside the IST cash session: Mon–Fri 09:15–15:30. Holidays are NOT consulted — the only
 * available frontend calendar is date-only, so safety gates conservatively treat a weekday holiday's
 * session window as closed to manual actions while refresh gates may perform a harmless spare refetch.
 */
export function isMarketHoursIst(now: Date = new Date()): boolean {
  const ist = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
  const day = ist.getDay();
  if (day === 0 || day === 6) return false;
  const mins = ist.getHours() * 60 + ist.getMinutes();
  return mins >= 9 * 60 + 15 && mins <= 15 * 60 + 30;
}
