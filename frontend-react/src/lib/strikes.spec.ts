import { describe, expect, it } from 'vitest';
import { nearestStrike } from './strikes.ts';

describe('nearestStrike', () => {
  it('falls back to the first strike when spot is null', () => {
    expect(nearestStrike(['17900', '18000', '18100'], null)).toBe('17900');
  });

  it('returns null when spot is null and there are no strikes', () => {
    expect(nearestStrike([], null)).toBeNull();
  });

  it('returns null when there are no strikes even with a spot', () => {
    expect(nearestStrike([], '18000')).toBeNull();
  });

  it('returns an exact match', () => {
    expect(nearestStrike(['17900', '18000', '18100'], '18000')).toBe('18000');
  });

  it('picks the closest strike by absolute distance (below or above)', () => {
    expect(nearestStrike(['100', '200', '300'], '140')).toBe('100');
    expect(nearestStrike(['100', '200', '300'], '190')).toBe('200');
  });

  it('uses exact decimal distance, not float rounding', () => {
    // |17950.5-17900| = 50.5 vs |17950.5-18000| = 49.5 -> 18000 is nearer
    expect(nearestStrike(['17900', '18000'], '17950.5')).toBe('18000');
  });

  it('keeps the first strike on a distance tie (strict-less-than)', () => {
    // spot exactly between: both 50 away -> first (100) wins
    expect(nearestStrike(['100', '200'], '150')).toBe('100');
  });
});
