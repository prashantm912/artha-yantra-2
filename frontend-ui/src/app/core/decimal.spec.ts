import { describe, expect, it } from 'vitest';
import { compareDecimal, formatDecimal, isNegative } from './decimal';

describe('decimal utility (exact strings, never parseFloat)', () => {
  it('compares beyond double precision', () => {
    expect(compareDecimal('21750.05000000001', '21750.05')).toBeGreaterThan(0);
    expect(compareDecimal('0.1', '0.10')).toBe(0);
    expect(compareDecimal('-2.5', '1')).toBeLessThan(0);
    expect(compareDecimal('-2.5', '-2.4')).toBeLessThan(0);
    expect(compareDecimal('100', '99.999999')).toBeGreaterThan(0);
    expect(compareDecimal('-0', '0')).toBe(0);
  });

  it('formats without floating arithmetic', () => {
    expect(formatDecimal('21750.5', 2)).toBe('21750.50');
    expect(formatDecimal('0.761111119', 4)).toBe('0.7611');
    expect(formatDecimal('-3.1', 2)).toBe('-3.10');
    expect(formatDecimal('42', 0)).toBe('42');
  });

  it('detects sign', () => {
    expect(isNegative('-0.0001')).toBe(true);
    expect(isNegative('0')).toBe(false);
  });
});
