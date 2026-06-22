import { describe, expect, it } from 'vitest';
import { foldVixIndex } from './vixIndexSeries.ts';
import type { MarketCandle } from '../api/types.ts';

function c(bucketHhmm: string, close: string): MarketCandle {
  return {
    exchange: 'NSE',
    tradingsymbol: 'X',
    interval: '1m',
    bucket: `2026-06-20T${bucketHhmm}:00+05:30`,
    open: close,
    high: close,
    low: close,
    close,
    volume: 0,
    oi: null,
    source: 'LIVE',
  };
}

describe('foldVixIndex', () => {
  it('aligned minutes → close decimal-strings become numeric coordinates', () => {
    const r = foldVixIndex(
      [c('09:15', '14.35'), c('09:18', '13.95')],
      [c('09:15', '23923.9'), c('09:18', '23909.35')],
    );
    expect(r.xAxis).toEqual(['09:15', '09:18']);
    expect(r.vix).toEqual([14.35, 13.95]);
    expect(r.price).toEqual([23923.9, 23909.35]);
  });

  it('a gap on one series keeps an x slot with null (connectNulls bridges, never 0)', () => {
    const r = foldVixIndex(
      [c('09:15', '14.35')],
      [c('09:15', '23923.9'), c('09:16', '23910')],
    );
    expect(r.xAxis).toEqual(['09:15', '09:16']);
    expect(r.vix).toEqual([14.35, null]);
    expect(r.price).toEqual([23923.9, 23910]);
  });

  it('misaligned minutes form a sorted union axis with nulls on each missing side', () => {
    const r = foldVixIndex(
      [c('09:15', '14.3'), c('09:17', '14.1')],
      [c('09:16', '23900'), c('09:17', '23890')],
    );
    expect(r.xAxis).toEqual(['09:15', '09:16', '09:17']);
    expect(r.vix).toEqual([14.3, null, 14.1]);
    expect(r.price).toEqual([null, 23900, 23890]);
  });

  it('empty / null input → empty arrays', () => {
    expect(foldVixIndex([], null)).toEqual({ xAxis: [], vix: [], price: [] });
    expect(foldVixIndex(undefined, undefined)).toEqual({ xAxis: [], vix: [], price: [] });
  });

  it('a trailing-zero decimal close folds to a plain number', () => {
    const r = foldVixIndex([c('09:15', '14.00')], [c('09:15', '23923.90')]);
    expect(r.vix).toEqual([14]);
    expect(r.price).toEqual([23923.9]);
  });
});
