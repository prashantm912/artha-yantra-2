import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import {
  FuturesStore,
  type ScreenerRow,
  type TermStructure,
  alignBasis,
  groupBuildup,
  stateSeverity,
} from './futures.store';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

function row(symbol: string, label: string): ScreenerRow {
  return {
    exchange: 'NFO',
    tradingsymbol: symbol,
    latestClose: '18100.00',
    pastClose: '18000.00',
    value: '100.00',
    avgVolume: '500000',
    distanceFromHigh52w: '2.00',
    label,
  };
}

function term(): TermStructure {
  return {
    underlying: 'NIFTY 50',
    spot: '18050.00',
    state: 'CONTANGO',
    calendarSpread: '120.0000',
    stale: false,
    asOf: '2026-06-14T10:00:00+05:30',
    contracts: [
      {
        tradingsymbol: 'NIFTY26JUNFUT',
        expiry: '2026-06-25',
        daysToExpiry: 11,
        ltp: '18100.00',
        oi: 1000,
        volume: 500,
        basisAbsolute: '50.0000',
        basisAnnualized: '9.2000',
      },
      {
        tradingsymbol: 'NIFTY26JULFUT',
        expiry: '2026-07-28',
        daysToExpiry: 44,
        ltp: '18220.00',
        oi: 800,
        volume: 300,
        basisAbsolute: '170.0000',
        basisAnnualized: '7.8000',
      },
    ],
  };
}

describe('FuturesStore helpers', () => {
  it('alignBasis computes FUT−spot per shared bucket with exact decimals', () => {
    const points = alignBasis(
      [
        { bucket: '2026-06-10T00:00:00+05:30', close: '18100.00' },
        { bucket: '2026-06-11T00:00:00+05:30', close: '18150.00' },
      ],
      [
        { bucket: '2026-06-10T00:00:00+05:30', close: '18050.00' },
        { bucket: '2026-06-99', close: '0' }, // no matching FUT bucket -> dropped
      ],
    );
    expect(points).toHaveLength(1);
    expect(points[0].basis).toBe('50');
  });

  it('groupBuildup maps rows into the four server-classified tiles', () => {
    const tiles = groupBuildup([
      row('A', 'LONG_BUILDUP'),
      row('B', 'SHORT_COVERING'),
      row('C', 'LONG_BUILDUP'),
    ]);
    expect(tiles.map((t) => t.key)).toEqual([
      'LONG_BUILDUP',
      'SHORT_BUILDUP',
      'LONG_UNWINDING',
      'SHORT_COVERING',
    ]);
    expect(tiles[0].rows).toHaveLength(2);
    expect(tiles[3].rows).toHaveLength(1);
  });

  it('stateSeverity flags backwardation', () => {
    expect(stateSeverity('CONTANGO')).toBe('info');
    expect(stateSeverity('BACKWARDATION')).toBe('warn');
  });
});

describe('FuturesStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof FuturesStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: new FakeWs() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(FuturesStore);
    http.expectOne((r) => r.url.endsWith('/term-structure')).flush(term());
    http
      .expectOne((r) => r.url.endsWith('/screener'))
      .flush({
        items: [row('NIFTY26JUNFUT', 'LONG_BUILDUP'), row('BANKNIFTY26JUNFUT', 'SHORT_BUILDUP')],
      });
    for (const req of http.match((r) => r.url === '/api/v1/market/candles')) {
      const exchange = req.request.params.get('exchange');
      req.flush({
        items: [
          {
            bucket: '2026-06-10T00:00:00+05:30',
            close: exchange === 'NFO' ? '18100.00' : '18050.00',
          },
        ],
      });
    }
  });

  afterEach(() => http.verify());

  it('loads the term structure and front-contract basis history', () => {
    expect(store.term()?.state).toBe('CONTANGO');
    expect(store.term()?.contracts).toHaveLength(2);
    expect(store.basisSeries()).toHaveLength(1);
    expect(store.basisSeries()[0].basis).toBe('50');
  });

  it('groups the oi_buildup preset into tiles', () => {
    const tiles = store.buildupTiles();
    expect(tiles.find((t) => t.key === 'LONG_BUILDUP')?.rows).toHaveLength(1);
    expect(tiles.find((t) => t.key === 'SHORT_BUILDUP')?.rows).toHaveLength(1);
    expect(tiles.find((t) => t.key === 'LONG_UNWINDING')?.rows).toHaveLength(0);
  });
});
