import { describe, expect, it } from 'vitest';
import {
  compareDecimal,
  formatDecimal,
  isNegative,
  multiplyByInt,
  subtractDecimal,
} from './decimal.ts';

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

  it('subtracts exactly via BigInt scaling', () => {
    expect(subtractDecimal('18100.0000', '18050.5000')).toBe('49.5');
    expect(subtractDecimal('100', '40')).toBe('60');
    expect(subtractDecimal('18050.50', '18100.00')).toBe('-49.5');
    expect(subtractDecimal('0.10', '0.10')).toBe('0');
    expect(subtractDecimal('21750.05000000001', '21750.05')).toBe('0.00000000001');
  });

  it('multiplies a decimal by an integer exactly', () => {
    expect(multiplyByInt('1.25', 4)).toBe('5');
    expect(multiplyByInt('99.95', 50)).toBe('4997.5');
    expect(multiplyByInt('-0.05', 50)).toBe('-2.5');
    expect(multiplyByInt('100.00', 0)).toBe('0');
  });
});
