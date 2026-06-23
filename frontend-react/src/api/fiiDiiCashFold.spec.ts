import { describe, expect, it } from 'vitest';
import { foldFiiDiiCash } from './fiiDiiCashFold.ts';
import type { FiiDiiRow } from './types.ts';

const r = (tradeDate: string, category: string, buy: string, sell: string, net: string): FiiDiiRow => ({
  tradeDate,
  category,
  buyValue: buy,
  sellValue: sell,
  netValue: net,
});

describe('foldFiiDiiCash', () => {
  it('pivots FII/DII rows per date and computes In Market = FII Net + DII Net', () => {
    const out = foldFiiDiiCash([
      r('2026-06-15', 'FII', '15650.20', '15450.15', '200.05'),
      r('2026-06-15', 'DII', '21080.90', '17891.64', '3189.26'),
    ]);
    expect(out).toHaveLength(1);
    expect(out[0].fiiNet).toBe('200.05');
    expect(out[0].diiNet).toBe('3189.26');
    expect(out[0].inMarket).toBe('3389.31'); // 200.05 + 3189.26
  });

  it('handles a negative FII net in the In-Market sum', () => {
    const out = foldFiiDiiCash([
      r('2024-12-23', 'FII', '8705.49', '8874.20', '-168.71'),
      r('2024-12-23', 'DII', '11083.76', '8856.08', '2227.68'),
    ]);
    expect(out[0].inMarket).toBe('2058.97'); // −168.71 + 2227.68
  });

  it('matches the Upstox "FII/FPI" category label (not just exact "FII")', () => {
    // The live Upstox feed labels the FII side "FII/FPI"; an exact === 'FII' match blanked the whole
    // FII column + In-Market. Prefix match must populate both. (live-verified 2026-06-23)
    const out = foldFiiDiiCash([
      r('2026-06-22', 'FII/FPI', '12000.00', '12635.91', '-635.91'),
      r('2026-06-22', 'DII', '20000.00', '18964.28', '1035.72'),
    ]);
    expect(out[0].fiiNet).toBe('-635.91');
    expect(out[0].diiNet).toBe('1035.72');
    expect(out[0].inMarket).toBe('399.81'); // −635.91 + 1035.72
  });

  it('sorts dates newest first', () => {
    const out = foldFiiDiiCash([
      r('2026-06-14', 'FII', '1', '1', '0'),
      r('2026-06-16', 'FII', '1', '1', '0'),
      r('2026-06-15', 'FII', '1', '1', '0'),
    ]);
    expect(out.map((x) => x.tradeDate)).toEqual(['2026-06-16', '2026-06-15', '2026-06-14']);
  });
});
