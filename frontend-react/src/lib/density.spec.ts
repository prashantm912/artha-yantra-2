import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loadDensity, saveDensity } from './density.ts';

const KEY = 'ay.chainDensity';

describe('density load/save', () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('defaults to compact when nothing is saved', () => {
    expect(loadDensity()).toBe('compact');
  });

  it('loads a valid saved value', () => {
    localStorage.setItem(KEY, 'comfortable');
    expect(loadDensity()).toBe('comfortable');
  });

  it('ignores a garbage saved value and returns the default', () => {
    localStorage.setItem(KEY, 'nonsense');
    expect(loadDensity()).toBe('compact');
  });

  it('save then load round-trips', () => {
    saveDensity('comfortable');
    expect(localStorage.getItem(KEY)).toBe('comfortable');
    expect(loadDensity()).toBe('comfortable');
  });

  it('returns the default when localStorage.getItem throws (private mode)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('private mode');
    });
    expect(loadDensity()).toBe('compact');
  });

  it('swallows a throwing setItem (private mode) without raising', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('private mode');
    });
    expect(() => saveDensity('compact')).not.toThrow();
  });
});
