import { describe, expect, it } from 'vitest';
import { oiIntMeta, type OiInterpretation } from './oiInterpretation.ts';

describe('oiIntMeta (4-state OI interpretation)', () => {
  it('maps every state to a distinct text label (non-colour cue)', () => {
    const all: OiInterpretation[] = [
      'LONG_BUILDUP',
      'SHORT_BUILDUP',
      'SHORT_COVERING',
      'LONG_UNWINDING',
    ];
    const labels = all.map((s) => oiIntMeta(s).label);
    expect(labels).toEqual(['Long Build Up', 'Short Build Up', 'Shorts Covering', 'Long Unwinding']);
    expect(new Set(labels).size).toBe(4); // all distinct
  });

  it('carries severity + price/OI arrow', () => {
    expect(oiIntMeta('LONG_BUILDUP').severity).toBe('success');
    expect(oiIntMeta('SHORT_BUILDUP').severity).toBe('danger');
    expect(oiIntMeta('SHORT_COVERING').severity).toBe('info');
    expect(oiIntMeta('LONG_UNWINDING').severity).toBe('warn');
    expect(oiIntMeta('LONG_BUILDUP').arrow).toBe('price up · OI up');
  });
});
