import { describe, expect, it } from 'vitest';
import { chainSpot, foldStrikes, maxOptionOi } from './oiFold.ts';
import type { OiStrikePoint } from './types.ts';

const pt = (over: Partial<OiStrikePoint>): OiStrikePoint => ({
  bucket: '2026-06-16T09:15:00',
  strike: '22500',
  optionType: 'CE',
  ltp: '100',
  oi: 1000,
  oiChange: 50,
  iv: '12.5',
  spot: '22480',
  ...over,
});

describe('foldStrikes', () => {
  it('folds CE+PE into one row per strike, ascending by decimal strike, carrying spot', () => {
    const rows = foldStrikes([
      pt({ strike: '22600', optionType: 'PE', oi: 800 }),
      pt({ strike: '22500', optionType: 'CE', oi: 1000 }),
      pt({ strike: '22500', optionType: 'PE', oi: 900 }),
    ]);
    expect(rows.map((r) => r.strike)).toEqual(['22500', '22600']); // sorted
    expect(rows[0].ce?.oi).toBe(1000);
    expect(rows[0].pe?.oi).toBe(900);
    expect(rows[1].ce).toBeNull();
    expect(rows[1].pe?.oi).toBe(800);
    expect(rows[0].spot).toBe('22480');
  });

  it('maxOptionOi reduces (never spreads) and floors at 1', () => {
    expect(maxOptionOi([pt({ oi: 5 }), pt({ oi: 12 }), pt({ oi: null })])).toBe(12);
    expect(maxOptionOi([])).toBe(1);
  });

  it('chainSpot picks the first non-null spot', () => {
    expect(chainSpot([pt({ spot: null }), pt({ spot: '22490' })])).toBe('22490');
    expect(chainSpot([pt({ spot: null })])).toBeNull();
  });
});
