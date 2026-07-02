import { describe, expect, it } from 'vitest';
import { foldActiveStrikeIvSeries, foldActiveStrikeSeries, ivPct } from './activeStrikesFold.ts';
import type { ActiveStrikeIvPoint, ActiveStrikeOiPoint, SentimentPoint } from './types.ts';

const B0 = '2026-06-20T09:15:00+05:30';
const B1 = '2026-06-20T09:18:00+05:30';

describe('foldActiveStrikeSeries', () => {
  it('merges the OI + sentiment series on a shared HH:mm bucket axis, oldest-first', () => {
    const oi: ActiveStrikeOiPoint[] = [
      { bucket: B0, ceOi: 1000, peOi: 1000 },
      { bucket: B1, ceOi: 900, peOi: 1100 },
    ];
    const sent: SentimentPoint[] = [
      { bucket: B0, sentimentPct: '25.00' },
      { bucket: B1, sentimentPct: '-25.00' },
    ];
    const r = foldActiveStrikeSeries(oi, sent);
    expect(r.times).toEqual(['09:15', '09:18']);
    expect(r.callOi).toEqual([1000, 900]);
    expect(r.putOi).toEqual([1000, 1100]);
    expect(r.sentiment).toEqual([25, -25]);
  });

  it('null sentimentPct → null point; a bucket missing from one series fills null on that side', () => {
    const r = foldActiveStrikeSeries(
      [{ bucket: B0, ceOi: 500, peOi: 600 }],
      [
        { bucket: B0, sentimentPct: null },
        { bucket: B1, sentimentPct: '10.00' }, // bucket with no OI point
      ],
    );
    expect(r.times).toEqual(['09:15', '09:18']);
    expect(r.callOi).toEqual([500, null]); // B1 absent from OI series
    expect(r.sentiment).toEqual([null, 10]);
  });

  it('null/undefined inputs → empty series', () => {
    expect(foldActiveStrikeSeries(null, undefined)).toEqual({
      times: [],
      callOi: [],
      putOi: [],
      sentiment: [],
    });
  });
});

describe('foldActiveStrikeIvSeries', () => {
  it('maps the IV series to HH:mm + IVs in display PERCENT (solver decimals ×100) + numeric price', () => {
    const iv: ActiveStrikeIvPoint[] = [
      { bucket: B0, ceIv: '0.106400', peIv: '0.118200', price: '57700.0000' },
      { bucket: B1, ceIv: '0.152500', peIv: '0.214000', price: '57750.0000' },
    ];
    const r = foldActiveStrikeIvSeries(iv);
    expect(r.times).toEqual(['09:15', '09:18']);
    expect(r.callIv).toEqual([10.64, 15.25]);
    expect(r.putIv).toEqual([11.82, 21.4]);
    expect(r.price).toEqual([57700, 57750]);
  });

  it('a null IV leg rides through as null (line gaps, not a zero)', () => {
    const r = foldActiveStrikeIvSeries([{ bucket: B0, ceIv: null, peIv: '0.192', price: '57700' }]);
    expect(r.callIv).toEqual([null]);
    expect(r.putIv).toEqual([19.2]);
  });

  it('null/undefined input → empty arrays', () => {
    expect(foldActiveStrikeIvSeries(null)).toEqual({ times: [], callIv: [], putIv: [], price: [] });
  });

  it('ivPct renders a decimal-vol string as one-decimal percent', () => {
    expect(ivPct('0.1064')).toBe('10.6');
    expect(ivPct(null)).toBeNull();
  });
});
