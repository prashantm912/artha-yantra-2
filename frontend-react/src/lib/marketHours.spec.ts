import { describe, expect, it } from 'vitest';
import { isMarketHoursIst } from './marketHours.ts';

// Instants chosen in UTC; IST = UTC+05:30.
const at = (iso: string) => new Date(iso);

describe('isMarketHoursIst', () => {
  it('true inside the session, false outside and on weekends', () => {
    expect(isMarketHoursIst(at('2026-07-02T06:00:00Z'))).toBe(true); // Thu 11:30 IST
    expect(isMarketHoursIst(at('2026-07-02T03:44:00Z'))).toBe(false); // Thu 09:14 IST
    expect(isMarketHoursIst(at('2026-07-02T03:45:00Z'))).toBe(true); // Thu 09:15 IST
    expect(isMarketHoursIst(at('2026-07-02T10:00:00Z'))).toBe(true); // Thu 15:30 IST
    expect(isMarketHoursIst(at('2026-07-02T10:01:00Z'))).toBe(false); // Thu 15:31 IST
    expect(isMarketHoursIst(at('2026-07-04T06:00:00Z'))).toBe(false); // Saturday
  });
});
