import { describe, expect, it } from 'vitest';
import { foldParticipantOi } from './participantOiFold.ts';
import type { ParticipantOiRow } from './types.ts';

function row(p: Partial<ParticipantOiRow>): ParticipantOiRow {
  return {
    tradeDate: '2026-06-15',
    clientType: 'FII',
    futureIndexLong: 0, futureIndexShort: 0,
    futureStockLong: 0, futureStockShort: 0,
    optionIndexCallLong: 0, optionIndexPutLong: 0, optionIndexCallShort: 0, optionIndexPutShort: 0,
    optionStockCallLong: 0, optionStockPutLong: 0, optionStockCallShort: 0, optionStockPutShort: 0,
    totalLongContracts: 1_000_000, totalShortContracts: 1_000_000,
    ...p,
  };
}

describe('foldParticipantOi', () => {
  it('pivots into 6 segments and diffs the latest vs prior date', () => {
    const groups = foldParticipantOi([
      row({ tradeDate: '2026-06-12', futureIndexLong: 40000, futureIndexShort: 280000 }),
      row({ tradeDate: '2026-06-15', futureIndexLong: 41074, futureIndexShort: 282315 }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].participant).toBe('FII');
    const futIdx = groups[0].segments.find((s) => s.segment === 'Future Index')!;
    expect(futIdx.long).toBe(41074);
    expect(futIdx.totalDiff).toBe(41074 - 282315);
    expect(futIdx.chngLong).toBe(1074); // 41074 − 40000
    expect(futIdx.chngShort).toBe(2315); // 282315 − 280000
    expect(futIdx.chngTotal).toBe(1074 - 2315); // −1241
    expect(futIdx.interpretation?.label).toBe('Bearish');
    expect(futIdx.longPct).toBe('4.1'); // 41074 / 1,000,000 * 100
  });

  it('is Neutral when net-long but the long leg is falling (not just chngTotal sign)', () => {
    const groups = foldParticipantOi([
      row({ tradeDate: '2026-06-12', futureStockLong: 100, futureStockShort: 50 }),
      row({ tradeDate: '2026-06-15', futureStockLong: 90, futureStockShort: 40 }), // net long (50) but ΔLong −10
    ]);
    const futStk = groups[0].segments.find((s) => s.segment === 'Future Stock')!;
    expect(futStk.totalDiff).toBe(50); // net long
    expect(futStk.chngLong).toBe(-10); // long falling
    expect(futStk.interpretation?.label).toBe('Neutral'); // not Bullish despite chngTotal 0
  });

  it('leaves change columns null when only one date is present', () => {
    const groups = foldParticipantOi([row({ futureIndexLong: 41074, futureIndexShort: 282315 })]);
    const futIdx = groups[0].segments.find((s) => s.segment === 'Future Index')!;
    expect(futIdx.chngLong).toBeNull();
    expect(futIdx.interpretation).toBeNull();
  });

  it('orders participants FII > Pro > DII > Client', () => {
    const groups = foldParticipantOi([
      row({ clientType: 'Client' }),
      row({ clientType: 'FII' }),
      row({ clientType: 'DII' }),
      row({ clientType: 'Pro' }),
    ]);
    expect(groups.map((g) => g.participant)).toEqual(['FII', 'Pro', 'DII', 'Client']);
  });
});
