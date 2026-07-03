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

  it('rounds half-up on the first dropped digit (audit format-decimal-truncates)', () => {
    expect(formatDecimal('0.66666667', 4)).toBe('0.6667'); // was 0.6666 truncated
    expect(formatDecimal('0.58999', 4)).toBe('0.5900');
    expect(formatDecimal('0.69995', 2)).toBe('0.70'); // only the first dropped digit decides
    expect(formatDecimal('0.6949', 2)).toBe('0.69'); // 4 stays down
    expect(formatDecimal('9.999', 2)).toBe('10.00'); // carry into the integer part
    expect(formatDecimal('0.5', 0)).toBe('1'); // zero fraction digits still round
    expect(formatDecimal('-0.66666667', 4)).toBe('-0.6667'); // half away from zero on negatives
    expect(formatDecimal('21750.50', 2)).toBe('21750.50'); // exact-scale inputs untouched
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

  // Java BigDecimal.toString() emits scientific notation for some scales (a zero %-change row on
  // the breadth page arrived as `0E-2`), which the digit-walking parser mis-read as a non-zero
  // positive int → a bogus `+0E-2.00%` cell.
  it('reads scientific-notation strings (BigDecimal.toString edge cases)', () => {
    expect(compareDecimal('0E-2', '0')).toBe(0);
    expect(isNegative('0E-2')).toBe(false);
    expect(formatDecimal('0E-2', 2)).toBe('0.00');
    expect(formatDecimal('1.5E+3', 2)).toBe('1500.00');
    expect(formatDecimal('-2.5E-3', 4)).toBe('-0.0025');
    expect(compareDecimal('1E2', '100')).toBe(0);
  });
});
