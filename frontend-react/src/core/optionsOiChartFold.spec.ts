import { describe, expect, it } from 'vitest';
import { foldOptionsOiChart } from './optionsOiChartFold.ts';
import type { OiStrikePoint } from '../api/types.ts';

function pt(p: Partial<OiStrikePoint>): OiStrikePoint {
  return {
    bucket: '2026-06-20T09:15:00+05:30',
    strike: '57200',
    optionType: 'CE',
    ltp: '780',
    oi: 1000,
    oiChange: 0,
    iv: null,
    spot: '57250',
    ...p,
  };
}

describe('foldOptionsOiChart', () => {
  it('splits CE/PE per bucket into Call/Put OI + premium arrays, oldest-first', () => {
    const viz = foldOptionsOiChart([
      pt({ bucket: '2026-06-20T09:15:00+05:30', optionType: 'CE', oi: 137250, ltp: '811.25' }),
      pt({ bucket: '2026-06-20T09:15:00+05:30', optionType: 'PE', oi: 90450, ltp: '749.10' }),
      pt({ bucket: '2026-06-20T09:20:00+05:30', optionType: 'CE', oi: 140820, ltp: '782.45' }),
      // PE missing at 09:20 → null gap on both PE arrays.
    ]);
    expect(viz.times).toEqual(['09:15', '09:20']);
    expect(viz.callOi).toEqual([137250, 140820]);
    expect(viz.putOi).toEqual([90450, null]);
    expect(viz.callPrice).toEqual([811.25, 782.45]); // premium (ltp) string → number
    expect(viz.putPrice).toEqual([749.1, null]);
  });

  it('returns empty arrays for no points', () => {
    const viz = foldOptionsOiChart([]);
    expect(viz.times).toEqual([]);
    expect(viz.callOi).toEqual([]);
    expect(viz.putPrice).toEqual([]);
  });
});
