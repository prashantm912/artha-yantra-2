import { describe, expect, it } from 'vitest';
import { toMultiLegSeries } from './multiLegOiSeries.ts';
import type { MultiOi } from '../api/types.ts';

describe('toMultiLegSeries', () => {
  it('folds legs + spot onto a unified oldest-first bucket axis, with null gaps + decimal→number price', () => {
    const data: MultiOi = {
      underlying: 'NIFTY',
      expiry: '2026-06-25',
      interval: '5m',
      asOf: '2026-06-20T09:20:00+05:30',
      items: [
        {
          leg: '57200 CE',
          points: [
            { bucket: '2026-06-20T09:15:00+05:30', oi: 134820 },
            { bucket: '2026-06-20T09:20:00+05:30', oi: 140000 },
          ],
        },
        // 57100 PE is missing the 09:15 bucket → a null gap there.
        { leg: '57100 PE', points: [{ bucket: '2026-06-20T09:20:00+05:30', oi: 92000 }] },
      ],
      spot: [
        { bucket: '2026-06-20T09:15:00+05:30', spot: '57250.00' },
        { bucket: '2026-06-20T09:20:00+05:30', spot: '57300.00' },
      ],
    };
    const s = toMultiLegSeries(data);
    expect(s.times).toEqual(['09:15', '09:20']);
    expect(s.legs[0].label).toBe('57200 CE');
    expect(s.legs[0].oi).toEqual([134820, 140000]);
    expect(s.legs[1].oi).toEqual([null, 92000]); // gap at 09:15 (bucket-key merge, not index)
    expect(s.price).toEqual([57250, 57300]); // decimal string → number at the boundary
  });

  it('returns empty arrays for null/undefined', () => {
    const s = toMultiLegSeries(null);
    expect(s.times).toEqual([]);
    expect(s.legs).toEqual([]);
    expect(s.price).toEqual([]);
  });
});
