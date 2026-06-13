import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import { formIndicators, parseDoc, withField } from './strategy-form';

const YAML = `schema: strategy-schema/v1
id: x
name: "Test"
indicators:
  - { name: EMA, alias: ema_fast, weight: 1.0 }
  - { name: RSI, alias: rsi, weight: 0.5, optional: true }
`;

describe('strategy-form (CD-11 form ↔ YAML sync)', () => {
  it('parseDoc returns the object for valid YAML and null for invalid', () => {
    expect(parseDoc(YAML)?.name).toBe('Test');
    expect(parseDoc(': : not yaml :')).toBeNull();
  });

  it('formIndicators reads alias/name/weight/optional', () => {
    const inds = formIndicators(parseDoc(YAML));
    expect(inds.map((i) => i.alias)).toEqual(['ema_fast', 'rsi']);
    expect(inds[0].weight).toBe(1);
    expect(inds[0].optional).toBe(false);
    expect(inds[1].optional).toBe(true);
  });

  it('withField writes a weight and round-trips through YAML', () => {
    const next = withField(YAML, (d) => {
      d.indicators![0]['weight'] = 2.5;
    });
    const parsed = parse(next) as { indicators: { weight: number }[] };
    expect(parsed.indicators[0].weight).toBe(2.5);
  });

  it('withField removes the optional flag when toggled off', () => {
    const next = withField(YAML, (d) => {
      delete d.indicators![1]['optional'];
    });
    const parsed = parse(next) as { indicators: { optional?: boolean }[] };
    expect(parsed.indicators[1].optional).toBeUndefined();
  });

  it('withField returns the original text when the YAML cannot be parsed', () => {
    const bad = ': : nope';
    expect(withField(bad, () => undefined)).toBe(bad);
  });
});
