import { describe, expect, it } from 'vitest';
import { bucketHhmm, foldIndividualOi, foldPcrPrice } from './oiStatsFold.ts';
import type { OiStrikePoint, TrendPoint } from './types.ts';

function pt(
  strike: string,
  type: 'CE' | 'PE',
  oi: number,
  oiChange: number,
  spot: string | null = '57000',
): OiStrikePoint {
  return { bucket: '2026-06-20T13:45:00+05:30', strike, optionType: type, ltp: null, oi, oiChange, iv: null, spot };
}

function tp(bucket: string, ceOi: number, peOi: number, spot: string | null): TrendPoint {
  return { bucket, totalOi: ceOi + peOi, ceOi, peOi, spot, trend: 'FLAT' };
}

describe('foldIndividualOi', () => {
  const items = [
    pt('57100', 'CE', 1000, 200, '57050'),
    pt('57100', 'PE', 1500, -50, '57050'),
    pt('56900', 'CE', 800, 100, '57050'),
    pt('56900', 'PE', 1200, 300, '57050'),
  ];

  it('groups by strike into numeric-ascending Call/Put bars + the ATM strike', () => {
    const r = foldIndividualOi(items, false);
    expect(r.strikes).toEqual(['56900', '57100']); // numeric order, not lexical
    expect(r.ce).toEqual([800, 1000]);
    expect(r.pe).toEqual([1200, 1500]);
    expect(r.atm).toBe('57100'); // nearest 57050 spot
  });

  it('useChange switches the bar value from absolute OI to interval ΔOI', () => {
    const r = foldIndividualOi(items, true);
    expect(r.ce).toEqual([100, 200]);
    expect(r.pe).toEqual([300, -50]);
  });

  it('a strike present for only one side keeps an x slot with 0 on the missing side', () => {
    const r = foldIndividualOi([pt('57100', 'CE', 1000, 0)], false);
    expect(r.strikes).toEqual(['57100']);
    expect(r.pe).toEqual([0]);
  });

  it('empty input → empty ladder + null ATM', () => {
    const r = foldIndividualOi([], false);
    expect(r.strikes).toEqual([]);
    expect(r.atm).toBeNull();
  });
});

describe('foldPcrPrice', () => {
  it('derives PCR = peOi/ceOi (2dp) and price = spot per bucket', () => {
    const out = foldPcrPrice([
      tp('2026-06-20T09:16:00+05:30', 1000, 1090, '57320.3'),
      tp('2026-06-20T09:17:00+05:30', 2000, 2400, '57353.2'),
    ]);
    expect(out[0]).toEqual({ time: '09:16', pcr: 1.09, price: 57320.3 });
    expect(out[1].pcr).toBe(1.2);
  });

  it('PCR is null when ceOi is 0; price is null when spot is absent', () => {
    const out = foldPcrPrice([tp('2026-06-20T09:16:00+05:30', 0, 500, null)]);
    expect(out[0].pcr).toBeNull();
    expect(out[0].price).toBeNull();
  });
});

describe('bucketHhmm', () => {
  it('extracts HH:mm from an ISO bucket', () => {
    expect(bucketHhmm('2026-06-20T13:45:00+05:30')).toBe('13:45');
  });
});
