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
    expect(futIdx.interpretation?.label).toBe('Bearish'); // day-delta read: Chng-in-Total < 0
  });

  it('percentages are the participant SHARE OF THE SEGMENT market-wide (§9.2-1)', () => {
    // FII holds 32,476 of a 3,25,011 segment long book → ~10.0%; the old own-book denominator
    // (totalLongContracts) would have read a misleading fraction of the participant's whole book.
    const groups = foldParticipantOi([
      row({ clientType: 'FII', futureIndexLong: 32476, futureIndexShort: 292535 }),
      row({ clientType: 'Client', futureIndexLong: 292535, futureIndexShort: 32476 }),
    ]);
    const fii = groups.find((g) => g.participant === 'FII')!;
    const futIdx = fii.segments.find((s) => s.segment === 'Future Index')!;
    expect(futIdx.longPct).toBe('10.0'); // 32476 / (32476 + 292535)
    expect(futIdx.shortPct).toBe('90.0');
  });

  it('put segments read INVERTED on the day-delta (puts added = bearish)', () => {
    const groups = foldParticipantOi([
      row({ tradeDate: '2026-06-12', optionIndexPutLong: 1000, optionIndexPutShort: 500 }),
      row({ tradeDate: '2026-06-15', optionIndexPutLong: 2000, optionIndexPutShort: 500 }), // ΔTotal +1000
    ]);
    const puts = groups[0].segments.find((s) => s.segment === 'Option Index Put')!;
    expect(puts.chngTotal).toBe(1000);
    expect(puts.interpretation?.label).toBe('Bearish'); // long puts building
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
