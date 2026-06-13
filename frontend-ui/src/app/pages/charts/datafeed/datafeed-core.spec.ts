import { describe, expect, it, vi } from 'vitest';
import { ArthaYantraDatafeed, type RawCandle } from './datafeed-core';

const ms = (iso: string): number => Date.parse(iso);

/** A fake gateway returning daily candles spaced 1 day apart across [from, to]. */
function dailyFake(perCall: number[]): {
  fetch: (e: string, s: string, i: string, f: string, t: string, l: number) => Promise<RawCandle[]>;
  calls: number;
} {
  const state = { calls: 0 };
  const fetch = async (
    _e: string,
    _s: string,
    _i: string,
    fromIso: string,
    toIso: string,
  ): Promise<RawCandle[]> => {
    const n = perCall[Math.min(state.calls, perCall.length - 1)];
    state.calls++;
    const to = Date.parse(toIso);
    const out: RawCandle[] = [];
    for (let k = n; k >= 1; k--) {
      const t = to - k * 86_400_000;
      if (t < Date.parse(fromIso)) continue;
      const c = String(100 + (n - k));
      out.push({
        bucket: new Date(t).toISOString(),
        open: c,
        high: c,
        low: c,
        close: c,
        volume: 1,
      });
    }
    return out;
  };
  return {
    get fetch() {
      return fetch;
    },
    get calls() {
      return state.calls;
    },
  };
}

describe('ArthaYantraDatafeed core', () => {
  it('fetchBars returns at least `count` bars strictly before toMs', async () => {
    const gw = dailyFake([300]);
    const df = new ArthaYantraDatafeed(gw.fetch);
    const toMs = ms('2026-03-01T00:00:00+05:30');
    const bars = await df.fetchBars('NSE', 'RELIANCE', '1d', toMs, 50);
    expect(bars.length).toBeGreaterThanOrEqual(50);
    expect(bars.every((b) => b.time < toMs)).toBe(true);
  });

  it('fetchBars widens the window when a page is sparse', async () => {
    const fetch = vi.fn();
    // first call: only 5 bars; widened call: plenty
    let call = 0;
    fetch.mockImplementation(async (_e, _s, _i, fromIso: string, toIso: string) => {
      call++;
      const to = Date.parse(toIso);
      const count = call === 1 ? 5 : 80;
      const out: RawCandle[] = [];
      for (let k = count; k >= 1; k--) {
        const t = to - k * 86_400_000;
        if (t < Date.parse(fromIso)) continue;
        out.push({
          bucket: new Date(t).toISOString(),
          open: '1',
          high: '1',
          low: '1',
          close: '1',
          volume: 0,
        });
      }
      return out;
    });
    const df = new ArthaYantraDatafeed(fetch);
    const bars = await df.fetchBars('NSE', 'X', '1d', ms('2026-03-01T00:00:00+05:30'), 50);
    expect(fetch.mock.calls.length).toBeGreaterThan(1);
    expect(bars.length).toBeGreaterThanOrEqual(50);
  });

  it('aggregate opens a new bar on bucket rollover and updates high/low/close in place', () => {
    const df = new ArthaYantraDatafeed(async () => []);
    const t0 = ms('2026-02-03T09:15:10+05:30');
    const first = df.aggregate(
      { lastPrice: '100.00', cumulativeDayVolume: 10, timestampMs: t0 },
      '1m',
      null,
    );
    expect(first.rollover).toBe(false);
    expect(first.bar.open).toBe('100.00');

    // same minute → updates the in-progress bar (new high, new close)
    const t1 = ms('2026-02-03T09:15:40+05:30');
    const upd = df.aggregate(
      { lastPrice: '101.50', cumulativeDayVolume: 20, timestampMs: t1 },
      '1m',
      first.bar,
    );
    expect(upd.rollover).toBe(false);
    expect(upd.bar.high).toBe('101.50');
    expect(upd.bar.low).toBe('100.00');
    expect(upd.bar.close).toBe('101.50');

    // next minute → rollover, new bar
    const t2 = ms('2026-02-03T09:16:05+05:30');
    const next = df.aggregate(
      { lastPrice: '101.00', cumulativeDayVolume: 25, timestampMs: t2 },
      '1m',
      upd.bar,
    );
    expect(next.rollover).toBe(true);
    expect(next.bar.open).toBe('101.00');
  });
});
