import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { OiAnalyticsStore, foldStrikes, type OiStrikePoint } from './oi-analytics.store';
import { SymbolContextStore } from './symbol-context.store';

function point(
  strike: string,
  optionType: 'CE' | 'PE',
  oi: number,
  oiChange: number,
  spot: string | null,
): OiStrikePoint {
  return {
    bucket: '2026-06-15T10:00:00+05:30',
    strike,
    optionType,
    ltp: '100.00',
    oi,
    oiChange,
    iv: '12.5000',
    spot,
  };
}

describe('foldStrikes', () => {
  it('merges CE and PE legs into one row per strike, sorted ascending, carrying spot', () => {
    const rows = foldStrikes([
      point('22600', 'PE', 800, -10, '22480.00'),
      point('22500', 'CE', 1000, 50, '22480.00'),
      point('22500', 'PE', 1500, -20, null),
    ]);
    expect(rows.map((r) => r.strike)).toEqual(['22500', '22600']);
    expect(rows[0].ce?.oi).toBe(1000);
    expect(rows[0].pe?.oi).toBe(1500);
    expect(rows[0].spot).toBe('22480.00');
    expect(rows[1].ce).toBeNull();
    expect(rows[1].pe?.oi).toBe(800);
  });
});

describe('OiAnalyticsStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof OiAnalyticsStore>;
  let ctx: InstanceType<typeof SymbolContextStore>;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    ctx = TestBed.inject(SymbolContextStore);
    store = TestBed.inject(OiAnalyticsStore);
    // SymbolContextStore.onInit kicks an expiries load — drain it.
    http.expectOne((r) => r.url.endsWith('/expiries')).flush(['2026-06-25']);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  it('loadOptions fetches stats, active strikes and the folded chain', () => {
    ctx.setExpiry('2026-06-25');
    store.loadOptions();

    http
      .expectOne((r) => r.url.includes('/options/oi-stats'))
      .flush({ pcr: '1.5000', maxPain: '22500.00', ceOi: 1000, peOi: 1500, asOf: 'x' });
    http
      .expectOne((r) => r.url.includes('/options/active-strikes'))
      .flush({
        sentimentPct: '12.50',
        items: [{ strike: '22500', ceOi: 1000, peOi: 1500 }],
        asOf: 'x',
      });
    http
      .expectOne((r) => r.url.includes('/options/oi-analysis'))
      .flush({
        items: [
          point('22500', 'CE', 1000, 50, '22480.00'),
          point('22500', 'PE', 1500, -20, '22480.00'),
        ],
      });

    expect(store.oiStats()?.pcr).toBe('1.5000');
    expect(store.activeStrikes()?.sentimentPct).toBe('12.50');
    expect(store.chainRows()).toHaveLength(1);
    expect(store.chainRows()[0].pe?.oi).toBe(1500);
    expect(store.spot()).toBe('22480.00');
    expect(store.maxOptionOi()).toBe(1500);
  });

  it('maps a 422 DATA_GAP on the stats calls to null without throwing', () => {
    ctx.setExpiry('2026-06-25');
    store.loadOptions();

    http
      .expectOne((r) => r.url.includes('/options/oi-stats'))
      .flush({ code: 'DATA_GAP' }, { status: 422, statusText: 'Unprocessable Entity' });
    http
      .expectOne((r) => r.url.includes('/options/active-strikes'))
      .flush({ code: 'DATA_GAP' }, { status: 422, statusText: 'Unprocessable Entity' });
    http.expectOne((r) => r.url.includes('/options/oi-analysis')).flush({ items: [] });

    expect(store.oiStats()).toBeNull();
    expect(store.activeStrikes()).toBeNull();
    expect(store.chainRows()).toHaveLength(0);
  });

  it('skips the options calls when no expiry is selected', () => {
    ctx.setExpiry(null);
    store.loadOptions();
    http.expectNone((r) => r.url.includes('/options/oi-stats'));
    expect(store.oiStats()).toBeNull();
  });

  it('skips the calls in history mode until a date is chosen (backend would 400)', () => {
    ctx.setExpiry('2026-06-25');
    ctx.setMode('history');
    store.loadOptions();
    store.loadFutures();
    http.expectNone((r) => r.url.includes('/options/oi-stats'));
    http.expectNone((r) => r.url.includes('/futures/oi-analysis'));
    ctx.setDate('2026-06-12');
    store.loadFutures();
    http.expectOne((r) => r.url.includes('/futures/oi-analysis')).flush({ items: [] });
    expect(store.futures()).toHaveLength(0);
  });

  it('ignores a stale response once the selection has advanced', () => {
    ctx.setExpiry('2026-06-25');
    store.loadOptions(); // generation 1
    ctx.setExpiry('2026-07-30');
    store.loadOptions(); // generation 2 — supersedes 1

    http
      .match((r) => r.url.includes('/options/oi-stats'))
      .forEach((r, i) =>
        r.flush({ pcr: i === 0 ? '9.9999' : '1.5000', maxPain: '1', ceOi: 1, peOi: 1, asOf: 'x' }),
      );
    http
      .match((r) => r.url.includes('/options/active-strikes'))
      .forEach((r) => r.flush({ sentimentPct: '0', items: [], asOf: 'x' }));
    const analysis = http.match((r) => r.url.includes('/options/oi-analysis'));
    // Resolve the NEWER request first, then let the stale earlier one land last.
    analysis[1].flush({ items: [point('22700', 'CE', 7, 0, '1')] });
    analysis[0].flush({ items: [point('11111', 'CE', 1, 0, '1')] });

    expect(store.chainRows().map((r) => r.strike)).toEqual(['22700']);
    expect(store.oiStats()?.pcr).toBe('1.5000');
    expect(store.loadingOptions()).toBe(false);
  });

  it('loadFutures maps the items envelope (no expiry param)', () => {
    store.loadFutures();
    const req = http.expectOne((r) => r.url.includes('/futures/oi-analysis'));
    expect(req.request.params.has('expiry')).toBe(false);
    req.flush({
      items: [
        {
          bucket: 'b',
          tradingsymbol: 'NIFTY26JUNFUT',
          ltp: '22510.00',
          oi: 120000,
          oiChange: 3400,
        },
      ],
    });
    expect(store.futures()).toHaveLength(1);
    expect(store.futures()[0].tradingsymbol).toBe('NIFTY26JUNFUT');
    expect(store.maxFuturesOi()).toBe(120000);
  });

  it('loadSpurt maps the rollup summary to the oiInterpretation computed', () => {
    ctx.setExpiry('2026-06-25');
    store.loadSpurt();

    http
      .expectOne((r) => r.url.includes('/options/spurt'))
      .flush({
        items: [
          {
            strike: '22500',
            optionType: 'CE',
            ltp: '110',
            oi: 1200,
            oiChange: 200,
            spurtPct: '20.00',
            interpretation: 'LONG_BUILDUP',
          },
        ],
        summary: { interpretation: 'LONG_BUILDUP', spotDelta: '20', oiChange: 400 },
        asOf: 'x',
      });

    expect(store.spurt()?.items).toHaveLength(1);
    expect(store.oiInterpretation()).toBe('LONG_BUILDUP');
  });

  it('maps a 422 DATA_GAP on spurt to null (no badge)', () => {
    ctx.setExpiry('2026-06-25');
    store.loadSpurt();
    http
      .expectOne((r) => r.url.includes('/options/spurt'))
      .flush({ code: 'DATA_GAP' }, { status: 422, statusText: 'Unprocessable Entity' });
    expect(store.spurt()).toBeNull();
    expect(store.oiInterpretation()).toBeNull();
  });

  it('skips the spurt call when no expiry is selected', () => {
    ctx.setExpiry(null);
    store.loadSpurt();
    http.expectNone((r) => r.url.includes('/options/spurt'));
    expect(store.oiInterpretation()).toBeNull();
  });
});
