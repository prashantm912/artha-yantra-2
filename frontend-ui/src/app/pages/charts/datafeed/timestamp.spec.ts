import { describe, expect, it } from 'vitest';
import {
  IST_OFFSET_SECONDS,
  floorBucketMs,
  fromChartTime,
  isoToChartTime,
  toChartTime,
  type BusinessDay,
} from './timestamp';

const ms = (iso: string): number => Date.parse(iso);

describe('datafeed timestamp normalization (E-10.1)', () => {
  it('floors a 1m tick to its IST minute bucket', () => {
    expect(floorBucketMs(ms('2026-02-03T09:15:37+05:30'), '1m')).toBe(
      ms('2026-02-03T09:15:00+05:30'),
    );
  });

  it('floors 5m and 15m to IST marks (330-min offset is a multiple of these sizes)', () => {
    expect(floorBucketMs(ms('2026-02-03T09:17:30+05:30'), '5m')).toBe(
      ms('2026-02-03T09:15:00+05:30'),
    );
    expect(floorBucketMs(ms('2026-02-03T09:29:59+05:30'), '15m')).toBe(
      ms('2026-02-03T09:15:00+05:30'),
    );
  });

  it('floors 1h to the UTC-hour boundary (= :30 IST — the documented cagg alignment)', () => {
    expect(floorBucketMs(ms('2026-02-03T09:45:00+05:30'), '1h')).toBe(
      ms('2026-02-03T09:30:00+05:30'),
    );
  });

  it('floors 1d to IST midnight', () => {
    expect(floorBucketMs(ms('2026-02-03T14:22:00+05:30'), '1d')).toBe(
      ms('2026-02-03T00:00:00+05:30'),
    );
  });

  it('intraday chart time is epoch-seconds shifted +05:30 so the chart shows IST wall-clock', () => {
    const epoch = ms('2026-02-03T09:15:00+05:30');
    const t = toChartTime(epoch, '1m') as number;
    // the shifted value, read as a UTC clock, equals 09:15
    const asUtc = new Date(t * 1000).toISOString();
    expect(asUtc).toBe('2026-02-03T09:15:00.000Z');
    expect(t).toBe(Math.floor(epoch / 1000) + IST_OFFSET_SECONDS);
  });

  it('daily chart time is an unshifted BusinessDay of the IST date', () => {
    const day = toChartTime(ms('2026-02-03T00:00:00+05:30'), '1d') as BusinessDay;
    expect(day).toEqual({ year: 2026, month: 2, day: 3 });
  });

  it('round-trips intraday and daily times back to the real instant', () => {
    const intraEpoch = ms('2026-02-03T09:16:00+05:30');
    expect(fromChartTime(toChartTime(intraEpoch, '5m'))).toBe(Math.floor(intraEpoch / 1000) * 1000);
    const dayEpoch = ms('2026-02-03T00:00:00+05:30');
    expect(fromChartTime(toChartTime(dayEpoch, '1d'))).toBe(dayEpoch);
  });

  it('isoToChartTime matches toChartTime', () => {
    const iso = '2026-02-03T09:15:00+05:30';
    expect(isoToChartTime(iso, '1m')).toBe(toChartTime(ms(iso), '1m'));
  });
});
