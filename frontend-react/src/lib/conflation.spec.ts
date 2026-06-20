import { describe, expect, it, vi } from 'vitest';
import { ConflationBuffer } from './conflation.ts';

describe('ConflationBuffer (newest-per-key, one flush per frame)', () => {
  it('flushes only the newest value per key', () => {
    const flush = vi.fn();
    let scheduled: (() => void) | null = null;
    const buf = new ConflationBuffer<number>(flush, (cb) => {
      scheduled = cb;
    });

    buf.set('a', 1);
    buf.set('a', 2); // supersedes 1
    buf.set('b', 9);
    expect(flush).not.toHaveBeenCalled(); // not until the frame fires

    scheduled!();
    expect(flush).toHaveBeenCalledTimes(1);
    const batch = flush.mock.calls[0][0] as Map<string, number>;
    expect(batch.get('a')).toBe(2);
    expect(batch.get('b')).toBe(9);
  });

  it('arms a fresh flush after the previous one drains', () => {
    const flush = vi.fn();
    let scheduled: (() => void) | null = null;
    const buf = new ConflationBuffer<number>(flush, (cb) => {
      scheduled = cb;
    });

    buf.set('a', 1);
    scheduled!();
    buf.set('a', 5);
    scheduled!();

    expect(flush).toHaveBeenCalledTimes(2);
    expect((flush.mock.calls[1][0] as Map<string, number>).get('a')).toBe(5);
  });
});
