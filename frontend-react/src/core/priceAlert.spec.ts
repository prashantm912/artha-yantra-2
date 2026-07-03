import { describe, expect, it } from 'vitest';
import { crossedThreshold } from './priceAlert.ts';

describe('crossedThreshold', () => {
  it('upward cross (prev below, latest at/above) fires', () => {
    expect(crossedThreshold(99, 101, 100)).toBe(true);
    expect(crossedThreshold(99, 100, 100)).toBe(true); // latest lands exactly on the level
  });

  it('downward cross (prev above, latest at/below) fires', () => {
    expect(crossedThreshold(101, 99, 100)).toBe(true);
    expect(crossedThreshold(100.5, 100, 100)).toBe(true);
  });

  it('no cross when both sit on the same side of the level', () => {
    expect(crossedThreshold(101, 102, 100)).toBe(false);
    expect(crossedThreshold(98, 99, 100)).toBe(false);
  });

  it('first observation (null prev) never fires', () => {
    expect(crossedThreshold(null, 100, 100)).toBe(false);
  });

  it('a flat tick exactly on the level does not fire (no movement through it)', () => {
    expect(crossedThreshold(100, 100, 100)).toBe(false);
  });

  it('prev exactly on the level, moving away either direction, does not re-fire (already crossed)', () => {
    expect(crossedThreshold(100, 101, 100)).toBe(false);
    expect(crossedThreshold(100, 99, 100)).toBe(false);
  });

  it('non-finite inputs are inert', () => {
    expect(crossedThreshold(Number.NaN, 100, 100)).toBe(false);
    expect(crossedThreshold(99, Number.NaN, 100)).toBe(false);
  });
});
