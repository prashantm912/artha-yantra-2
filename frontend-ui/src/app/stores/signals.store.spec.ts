import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import { SIGNAL_RING_LIMIT, SignalsStore, type SignalDto } from './signals.store';

/** Fake WS client: hand-driven topic + reconnect streams. */
class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  readonly frames$ = new Subject<string>();
  topic(): typeof this.frames$ {
    return this.frames$;
  }
  activate(): void {}
}

function dto(id: number, overrides: Partial<SignalDto> = {}): SignalDto {
  return {
    id,
    exchange: 'NSE',
    tradingsymbol: 'SIGTEST',
    interval: '1m',
    signalType: 'ENTRY',
    side: 'BUY',
    entryPrice: '101.50',
    stopLoss: null,
    target: null,
    compositeScore: '0.7611',
    scoreBreakdown: {
      composite: 0.7611,
      threshold: 0.5,
      passed: true,
      requiredComposite: 0.7611,
      optionalMinScore: 0.6,
      optionalGateMargin: 0.15,
      weightDenominator: 1,
      gate: { kind: 'all', passed: true, children: [] },
      indicators: [],
    },
    status: 'ACTIVE',
    generatedAt: '2026-06-13T10:00:00+05:30',
    ...overrides,
  };
}

describe('SignalsStore', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof SignalsStore>;

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
    store = TestBed.inject(SignalsStore);
    http.expectOne((req) => req.url === '/api/v1/signals').flush({ items: [dto(1)] });
    await framesFlushed();
  });

  afterEach(() => http.verify());

  it('seeds from the REST snapshot', () => {
    expect(store.items().map((item) => item.id)).toEqual([1]);
  });

  it('live frames unshift newest-first, deduped, after the rAF flush', async () => {
    ws.frames$.next(JSON.stringify(dto(2)));
    ws.frames$.next(JSON.stringify(dto(3)));
    ws.frames$.next(JSON.stringify(dto(2))); // duplicate id - ignored
    await framesFlushed();

    expect(store.items().map((item) => item.id)).toEqual([3, 2, 1]);
  });

  it('the live buffer is ring-bounded', async () => {
    for (let i = 0; i < SIGNAL_RING_LIMIT + 60; i++) {
      ws.frames$.next(JSON.stringify(dto(1000 + i)));
    }
    await framesFlushed();

    expect(store.items().length).toBe(SIGNAL_RING_LIMIT);
    expect(store.items()[0].id).toBe(1000 + SIGNAL_RING_LIMIT + 59); // newest survives
  });

  it('reconnect re-fetches the REST snapshot (at-least-once display)', () => {
    ws.reconnects$.next();
    http
      .expectOne((req) => req.url === '/api/v1/signals')
      .flush({ items: [dto(7)] });
    expect(store.items().map((item) => item.id)).toEqual([7]);
  });

  it('filters compose over status and symbol', async () => {
    ws.frames$.next(JSON.stringify(dto(5, { tradingsymbol: 'OTHER', status: 'EXPIRED' })));
    await framesFlushed();

    store.setSymbolFilter('SIG');
    expect(store.filtered().every((item) => item.tradingsymbol === 'SIGTEST')).toBe(true);
    store.setSymbolFilter(null);
    store.setStatusFilter('EXPIRED');
    expect(store.filtered().map((item) => item.id)).toEqual([5]);
    // setStatusFilter is page-changing - the page itself calls load(); none expected here
  });

  it('unparseable frames are dropped silently', async () => {
    ws.frames$.next('{{{not json');
    await framesFlushed();
    expect(store.items().map((item) => item.id)).toEqual([1]);
  });
});

/** rAF under jsdom falls back to the 16 ms setTimeout path in ConflationBuffer. */
function framesFlushed(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 40));
}
