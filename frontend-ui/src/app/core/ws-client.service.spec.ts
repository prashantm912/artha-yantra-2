import { describe, expect, it } from 'vitest';
import { nextReconnectDelay } from './ws-client.service';

describe('WsClient reconnect backoff (C-2.25: 1s -> 30s cap, full jitter)', () => {
  it('doubles toward the cap with deterministic randomness', () => {
    const fixed = () => 1; // jitter factor 1.0 -> the pure doubling envelope
    expect(nextReconnectDelay(1000, fixed)).toBe(2000);
    expect(nextReconnectDelay(2000, fixed)).toBe(4000);
    expect(nextReconnectDelay(16_000, fixed)).toBe(30_000);
    expect(nextReconnectDelay(30_000, fixed)).toBe(30_000); // capped
  });

  it('jitters within [0.5x, 1x] of the doubled target', () => {
    for (let i = 0; i < 200; i++) {
      const delay = nextReconnectDelay(4000);
      expect(delay).toBeGreaterThanOrEqual(4000); // 8000 * 0.5
      expect(delay).toBeLessThanOrEqual(8000);
    }
  });

  it('recovers from a zero/negative seed to the 1s floor', () => {
    const fixed = () => 1;
    expect(nextReconnectDelay(0, fixed)).toBe(1000);
    expect(nextReconnectDelay(-5, fixed)).toBe(1000);
  });
});
