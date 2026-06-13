import { describe, expect, it } from 'vitest';
import { degradationBand } from './degradation';

describe('degradationBand (guard-7 traffic light, E-12.3)', () => {
  it('< 0.3 is consistent (green)', () => {
    expect(degradationBand('0.10').band).toBe('consistent');
    expect(degradationBand(0.29).severity).toBe('success');
  });

  it('0.3–1.0 degrades (amber)', () => {
    expect(degradationBand('0.5').band).toBe('degrades');
    expect(degradationBand(0.3).severity).toBe('warn');
  });

  it('> 1.0 distrust (red)', () => {
    expect(degradationBand('1.4').band).toBe('distrust');
    expect(degradationBand(2).severity).toBe('danger');
  });

  it('null/undefined → n/a', () => {
    expect(degradationBand(null).band).toBe('na');
    expect(degradationBand(undefined).band).toBe('na');
  });

  it('weak train signal (train Sharpe < 0.5) suppresses to n/a', () => {
    const b = degradationBand('0.2', '0.4');
    expect(b.band).toBe('na');
    expect(b.label).toContain('weak train signal');
  });

  it('a healthy train Sharpe keeps the band', () => {
    expect(degradationBand('0.2', '1.5').band).toBe('consistent');
  });
});
