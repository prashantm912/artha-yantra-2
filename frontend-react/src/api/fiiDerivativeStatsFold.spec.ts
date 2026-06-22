import { describe, expect, it } from 'vitest';
import { foldFiiDerivativeStats } from './fiiDerivativeStatsFold.ts';
import type { FiiDerivativeRow } from './types.ts';

const r = (tradeDate: string, segment: string, net: string): FiiDerivativeRow => ({
  tradeDate,
  segment,
  buyValue: null,
  sellValue: null,
  netValue: net,
});

describe('foldFiiDerivativeStats', () => {
  it('pivots the four F&O segments into one row per date (oipulse 2026-06-22)', () => {
    const out = foldFiiDerivativeStats([
      r('2026-06-22', 'INDEX_FUTURES', '598.14'),
      r('2026-06-22', 'INDEX_OPTIONS', '3340.90'),
      r('2026-06-22', 'STOCK_FUTURES', '-1425.63'),
      r('2026-06-22', 'STOCK_OPTIONS', '-233.30'),
    ]);
    expect(out).toHaveLength(1);
    expect(out[0]).toEqual({
      tradeDate: '2026-06-22',
      idxFut: '598.14',
      idxOpt: '3340.90',
      stkFut: '-1425.63',
      stkOpt: '-233.30',
    });
  });

  it('sorts dates newest first and leaves a missing segment null', () => {
    const out = foldFiiDerivativeStats([
      r('2026-06-19', 'INDEX_FUTURES', '-792.02'),
      r('2026-06-22', 'INDEX_OPTIONS', '3340.90'),
    ]);
    expect(out.map((x) => x.tradeDate)).toEqual(['2026-06-22', '2026-06-19']);
    expect(out[0].idxFut).toBeNull(); // 06-22 has no INDEX_FUTURES row
    expect(out[1].idxOpt).toBeNull(); // 06-19 has no INDEX_OPTIONS row
  });
});
