import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Observable, Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import { MarketStore, type Instrument, type NormalizedTick } from './market.store';

/** Fake WS that records per-destination subscribe/unsubscribe counts and replays frames. */
class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  private readonly streams = new Map<string, Subject<string>>();
  readonly subCount = new Map<string, number>();
  topic(dest: string): Observable<string> {
    if (!this.streams.has(dest)) {
      this.streams.set(dest, new Subject<string>());
    }
    return new Observable<string>((obs) => {
      this.subCount.set(dest, (this.subCount.get(dest) ?? 0) + 1);
      const inner = this.streams.get(dest)!.subscribe(obs);
      return () => {
        inner.unsubscribe();
        this.subCount.set(dest, (this.subCount.get(dest) ?? 0) - 1);
      };
    });
  }
  activate(): void {}
  emit(dest: string, body: string): void {
    this.streams.get(dest)?.next(body);
  }
}

function tick(
  symbol: string,
  lastPrice: string,
  overrides: Partial<NormalizedTick> = {},
): NormalizedTick {
  return {
    exchange: 'NSE',
    tradingsymbol: symbol,
    lastPrice,
    cumulativeDayVolume: 100,
    openInterest: 0,
    timestamp: '2026-06-13T10:00:00+05:30',
    seq: 1,
    ...overrides,
  };
}

describe('MarketStore', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof MarketStore>;

  beforeEach(() => {
    localStorage.clear();
    ws = new FakeWs();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: ws },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(MarketStore);
    // onInit fires the system-status refresh
    http.expectOne((req) => req.url === '/api/v1/system/status').flush({ overall: 'UP' });
  });

  afterEach(() => http.verify());

  it('search() populates searchResults', () => {
    store.search('REL');
    const instrument: Instrument = {
      exchange: 'NSE',
      tradingsymbol: 'RELIANCE',
      instrumentToken: 738561,
      name: 'RELIANCE',
      segment: 'NSE',
      instrumentType: 'EQ',
      tickSize: '0.05',
      lotSize: 1,
      active: true,
    };
    http
      .expectOne((req) => req.url === '/api/v1/instruments/search' && req.params.get('q') === 'REL')
      .flush([instrument]);
    expect(store.searchResults().map((i) => i.tradingsymbol)).toEqual(['RELIANCE']);
  });

  it('track() subscribes to the per-symbol tick topic and seeds the latest snapshot', async () => {
    store.track(['NSE:RELIANCE']);
    http
      .expectOne((req) => req.method === 'POST' && req.url === '/api/v1/market/subscriptions')
      .flush({ exchange: 'NSE', tradingsymbol: 'RELIANCE', effectiveMode: 'quote' });
    http
      .expectOne(
        (req) =>
          req.url === '/api/v1/market/ticks/latest' && req.params.get('symbols') === 'NSE:RELIANCE',
      )
      .flush({ 'NSE:RELIANCE': tick('RELIANCE', '2950.00') });
    expect(ws.subCount.get('/topic/ticks.NSE.RELIANCE')).toBe(1);
    expect(store.ticks()['NSE:RELIANCE'].lastPrice).toBe('2950.00');

    ws.emit('/topic/ticks.NSE.RELIANCE', JSON.stringify(tick('RELIANCE', '2951.25', { seq: 2 })));
    await flushed();
    expect(store.ticks()['NSE:RELIANCE'].lastPrice).toBe('2951.25');
  });

  it('track()/untrack() refcount — the subscription survives until the last consumer releases', () => {
    store.track(['NSE:RELIANCE']);
    http
      .expectOne((req) => req.method === 'POST' && req.url === '/api/v1/market/subscriptions')
      .flush({ exchange: 'NSE', tradingsymbol: 'RELIANCE', effectiveMode: 'quote' });
    http.expectOne((req) => req.url === '/api/v1/market/ticks/latest').flush({});
    store.track(['NSE:RELIANCE']); // second consumer: no new sub, no new seed
    expect(ws.subCount.get('/topic/ticks.NSE.RELIANCE')).toBe(1);

    store.untrack(['NSE:RELIANCE']); // first consumer leaves — still one ref left
    expect(ws.subCount.get('/topic/ticks.NSE.RELIANCE')).toBe(1);
    store.untrack(['NSE:RELIANCE']); // last consumer leaves — torn down
    expect(ws.subCount.get('/topic/ticks.NSE.RELIANCE')).toBe(0);
    http
      .expectOne((req) => req.method === 'DELETE' && req.url === '/api/v1/market/subscriptions')
      .flush(null);
  });

  it('track() registers a UI ticker subscription and untrack() releases the same hold', () => {
    store.track(['NSE:TCS']);
    const sub = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/market/subscriptions',
    );
    expect(sub.request.body).toMatchObject({
      exchange: 'NSE',
      tradingsymbol: 'TCS',
      mode: 'quote',
      priority: 'ui',
    });
    const subscriber = (sub.request.body as { subscriber: string }).subscriber;
    expect(subscriber).toMatch(/^ui:/);
    sub.flush({ exchange: 'NSE', tradingsymbol: 'TCS', effectiveMode: 'quote' });
    http.expectOne((r) => r.url === '/api/v1/market/ticks/latest').flush({});

    store.untrack(['NSE:TCS']);
    const del = http.expectOne(
      (r) => r.method === 'DELETE' && r.url === '/api/v1/market/subscriptions',
    );
    expect(del.request.params.get('exchange')).toBe('NSE');
    expect(del.request.params.get('tradingsymbol')).toBe('TCS');
    expect(del.request.params.get('subscriber')).toBe(subscriber);
    del.flush(null);
  });

  it('widget collapse state persists to localStorage', () => {
    store.setWidgetCollapsed('jobs', true);
    expect(store.dashboardLayout()['jobs']).toBe(true);
    expect(JSON.parse(localStorage.getItem('ay.dashboard') ?? '{}').jobs).toBe(true);
  });
});

function flushed(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 40));
}
