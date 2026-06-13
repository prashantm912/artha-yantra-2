import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import {
  OptionsStore,
  type OptionChain,
  type OptionLeg,
  atmStrikeOf,
  transformHistory,
  vixStat,
  windowRows,
} from './options.store';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  readonly frames$ = new Subject<string>();
  topic(): typeof this.frames$ {
    return this.frames$;
  }
  activate(): void {}
}

function leg(symbol: string, ltp: string, iv: string, oi: number): OptionLeg {
  return {
    tradingsymbol: symbol,
    ltp,
    bid: ltp,
    ask: ltp,
    volume: 100,
    oi,
    iv,
    delta: '0.50',
    gamma: '0.001',
    theta: '-1.20',
    vega: '5.00',
    rho: '2.00',
    ivReason: 'OK',
    priceSource: 'LTP',
  };
}

function chain(overrides: Partial<OptionChain> = {}): OptionChain {
  return {
    underlying: 'NIFTY 50',
    expiry: '2026-06-16',
    spot: '18050.00',
    forward: '18075.00',
    pcr: '0.9500',
    stale: false,
    asOf: '2026-06-14T10:00:00+05:30',
    rows: [
      {
        strike: '18000',
        ce: leg('C0', '120.00', '0.155', 1000),
        pe: leg('P0', '40.00', '0.150', 800),
      },
      {
        strike: '18050',
        ce: leg('C1', '90.00', '0.150', 1200),
        pe: leg('P1', '60.00', '0.149', 1100),
      },
      {
        strike: '18100',
        ce: leg('C2', '60.00', '0.152', 900),
        pe: leg('P2', '95.00', '0.151', 1300),
      },
    ],
    ...overrides,
  };
}

describe('OptionsStore helpers', () => {
  it('transformHistory groups flat rows into a calls/strike/puts chain and computes PCR', () => {
    const built = transformHistory({
      underlying: 'NIFTY 50',
      expiry: '2026-06-16',
      ts: '2026-06-14T09:30:00+05:30',
      rows: [
        {
          strike: '18050',
          optionType: 'PE',
          tradingsymbol: 'P1',
          ltp: '60.00',
          bid: null,
          ask: null,
          volume: 10,
          oi: 200,
          oiChange: null,
          spotPrice: '18050.00',
          iv: '0.15',
          delta: null,
          gamma: null,
          theta: null,
          vega: null,
          rho: null,
          ivReason: 'OK',
          priceSource: 'LTP',
          forwardPrice: '18075.00',
          riskFreeRate: '0.065',
        },
        {
          strike: '18050',
          optionType: 'CE',
          tradingsymbol: 'C1',
          ltp: '90.00',
          bid: null,
          ask: null,
          volume: 10,
          oi: 100,
          oiChange: null,
          spotPrice: '18050.00',
          iv: '0.15',
          delta: null,
          gamma: null,
          theta: null,
          vega: null,
          rho: null,
          ivReason: 'OK',
          priceSource: 'LTP',
          forwardPrice: '18075.00',
          riskFreeRate: '0.065',
        },
      ],
    });
    expect(built.rows).toHaveLength(1);
    expect(built.rows[0].ce?.tradingsymbol).toBe('C1');
    expect(built.rows[0].pe?.tradingsymbol).toBe('P1');
    expect(built.spot).toBe('18050.00');
    expect(built.pcr).toBe('2.0000'); // 200 PE / 100 CE
    expect(built.stale).toBe(true);
  });

  it('atmStrikeOf picks the strike closest to spot', () => {
    expect(atmStrikeOf(chain().rows, '18048.00')).toBe('18050');
    expect(atmStrikeOf(chain().rows, '18099.00')).toBe('18100');
    expect(atmStrikeOf([], '18050')).toBeNull();
  });

  it('windowRows narrows to ±window strikes around ATM (0 = all)', () => {
    const rows = chain().rows;
    expect(windowRows(rows, '18050.00', 0)).toHaveLength(3);
    expect(windowRows(rows, '18050.00', 1).map((r) => r.strike)).toEqual([
      '18000',
      '18050',
      '18100',
    ]);
    expect(windowRows(rows, '18050.00', 0)).toBe(rows);
  });

  it('vixStat returns level + trailing percentile (share strictly below latest)', () => {
    const stat = vixStat([
      { bucket: 'd1', close: '12.00' },
      { bucket: 'd2', close: '20.00' },
      { bucket: 'd3', close: '14.00' },
    ]);
    expect(stat?.level).toBe('14.00');
    expect(stat?.percentile).toBe(33); // 1 of 3 below 14
    expect(vixStat([])).toBeNull();
  });
});

describe('OptionsStore', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof OptionsStore>;

  beforeEach(async () => {
    ws = new FakeWs();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: ws },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(OptionsStore);
    http.expectOne((r) => r.url.endsWith('/expiries')).flush(['2026-06-16']);
    http
      .expectOne((r) => r.url === '/api/v1/market/candles')
      .flush({
        items: [
          { bucket: 'd1', close: '12.00' },
          { bucket: 'd2', close: '14.50' },
        ],
      });
    http.expectOne((r) => r.url === '/api/v1/market/options/chain').flush(chain());
    await Promise.resolve();
  });

  afterEach(() => http.verify());

  it('seeds the chain from the REST snapshot and defaults the expiry', () => {
    expect(store.expiry()).toBe('2026-06-16');
    expect(store.windowedRows()).toHaveLength(3);
    expect(store.pcr()).toBe('0.9500');
    expect(store.vixStat()?.level).toBe('14.50');
  });

  it('a matching live frame replaces the chain and grows the PCR trend', () => {
    ws.frames$.next(JSON.stringify(chain({ pcr: '0.8800', spot: '18060.00' })));
    expect(store.pcr()).toBe('0.8800');
    expect(store.pcrTrend()).toHaveLength(1);
    expect(store.pcrTrend()[0].pcr).toBe('0.8800');
  });

  it('ignores a live frame for a different underlying', () => {
    ws.frames$.next(JSON.stringify(chain({ underlying: 'NIFTY BANK', pcr: '1.5000' })));
    expect(store.pcr()).toBe('0.9500'); // unchanged
    expect(store.pcrTrend()).toHaveLength(0);
  });

  it('history mode queries the snapshot endpoint and transforms it', () => {
    store.setHistoryMode(true);
    http
      .expectOne((r) => r.url === '/api/v1/market/options/chain/history')
      .flush({
        underlying: 'NIFTY 50',
        expiry: '2026-06-16',
        ts: '2026-06-10T09:30:00+05:30',
        rows: [
          {
            strike: '18000',
            optionType: 'CE',
            tradingsymbol: 'C0',
            ltp: '110.00',
            bid: null,
            ask: null,
            volume: 1,
            oi: 500,
            oiChange: null,
            spotPrice: '18000.00',
            iv: '0.16',
            delta: null,
            gamma: null,
            theta: null,
            vega: null,
            rho: null,
            ivReason: 'OK',
            priceSource: 'LTP',
            forwardPrice: '18020.00',
            riskFreeRate: '0.065',
          },
          {
            strike: '18000',
            optionType: 'PE',
            tradingsymbol: 'P0',
            ltp: '50.00',
            bid: null,
            ask: null,
            volume: 1,
            oi: 500,
            oiChange: null,
            spotPrice: '18000.00',
            iv: '0.15',
            delta: null,
            gamma: null,
            theta: null,
            vega: null,
            rho: null,
            ivReason: 'OK',
            priceSource: 'LTP',
            forwardPrice: '18020.00',
            riskFreeRate: '0.065',
          },
        ],
      });
    expect(store.stale()).toBe(true);
    expect(store.windowedRows()).toHaveLength(1);
    expect(store.windowedRows()[0].ce?.ltp).toBe('110.00');
  });

  it('a live frame is dropped while in history mode', () => {
    store.setHistoryMode(true);
    http
      .expectOne((r) => r.url === '/api/v1/market/options/chain/history')
      .flush({
        underlying: 'NIFTY 50',
        expiry: '2026-06-16',
        ts: '2026-06-10T09:30:00+05:30',
        rows: [],
      });
    ws.frames$.next(JSON.stringify(chain({ pcr: '0.7000' })));
    expect(store.pcr()).toBeNull(); // history payload had no rows -> pcr null, live ignored
  });
});
