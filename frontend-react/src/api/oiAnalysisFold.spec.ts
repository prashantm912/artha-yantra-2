import { describe, expect, it } from 'vitest';
import { bucketTime, foldOiAnalysis } from './oiAnalysisFold.ts';
import type { OiStrikePoint } from './types.ts';

function pt(bucket: string, type: 'CE' | 'PE', ltp: string, oi: number): OiStrikePoint {
  return {
    bucket,
    strike: '57100',
    optionType: type,
    ltp,
    oi,
    oiChange: 0,
    iv: null,
    spot: '57000',
  };
}

const B0 = '2026-06-20T09:15:00+05:30';
const B1 = '2026-06-20T09:18:00+05:30';
const B2 = '2026-06-20T09:21:00+05:30';

describe('foldOiAnalysis', () => {
  it('derives interval ΔOI, cumulative ΔOI, interpretation, and day-high break per side', () => {
    const rows = foldOiAnalysis([
      pt(B0, 'CE', '100', 1000),
      pt(B1, 'CE', '110', 1200), // ltp up, oi up → LONG_BUILDUP, new session high
      pt(B2, 'CE', '105', 1100), // ltp down, oi down → LONG_UNWINDING
      pt(B0, 'PE', '90', 800),
      pt(B1, 'PE', '85', 1000), // ltp down, oi up → SHORT_BUILDUP
    ]);

    expect(rows).toHaveLength(3); // 3 distinct buckets, oldest-first
    const b1 = rows[1];
    expect(b1.ce.oiChange).toBe(200); // 1200 − 1000
    expect(b1.ce.cumOiChange).toBe(200); // 1200 − first 1000
    expect(b1.ce.interpretation).toBe('LONG_BUILDUP');
    expect(b1.ce.dhBreak).toBe(true); // 110 > prior high 100
    expect(b1.pe.interpretation).toBe('SHORT_BUILDUP'); // 90→85 down, 800→1000 up

    const b2 = rows[2];
    expect(b2.ce.oiChange).toBe(-100); // 1100 − 1200
    expect(b2.ce.cumOiChange).toBe(100); // 1100 − first 1000
    expect(b2.ce.interpretation).toBe('LONG_UNWINDING'); // 110→105 down, 1200→1100 down
    expect(b2.ce.dhBreak).toBe(false);
    expect(b2.ce.dlBreak).toBe(false); // 105 not below running low 100
  });

  it('the first bucket has null interval deltas + no break; bucketTime extracts HH:mm', () => {
    const rows = foldOiAnalysis([pt(B0, 'CE', '100', 1000)]);
    expect(rows[0].ce.oiChange).toBeNull();
    expect(rows[0].ce.cumOiChange).toBe(0);
    expect(rows[0].ce.interpretation).toBeNull();
    expect(rows[0].ce.dhBreak).toBe(false);
    expect(bucketTime(B0)).toBe('09:15');
  });
});
