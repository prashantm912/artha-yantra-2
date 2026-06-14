import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import { PaperStore } from './paper.store';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

describe('PaperStore', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof PaperStore>;

  function flushSnapshot(): void {
    http.expectOne('/api/v1/paper/positions').flush({
      items: [
        {
          id: 1,
          exchange: 'NFO',
          tradingsymbol: 'NIFTY26JUN18000CE',
          side: 'BUY',
          qty: 50,
          avgEntryPrice: '120.0000',
          markPrice: '130.0000',
          unrealizedPnl: '500.00',
          realizedPnl: '0.0000',
          status: 'OPEN',
          openedAt: '2026-06-14T10:00:00+05:30',
        },
      ],
    });
    http.expectOne((r) => r.url === '/api/v1/paper/trades').flush({ items: [] });
    http.expectOne('/api/v1/paper/pnl').flush({
      points: [{ date: '2026-06-13', equity: '1200.00' }],
      summary: { realizedTotal: '1200.00', trades: 3, winRate: '0.6667', expectancy: '400.00' },
    });
  }

  beforeEach(() => {
    ws = new FakeWs();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: ws },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(PaperStore);
    flushSnapshot();
  });

  afterEach(() => http.verify());

  it('loads positions, trades and pnl on init', () => {
    expect(store.positions()).toHaveLength(1);
    expect(store.positions()[0].unrealizedPnl).toBe('500.00');
    expect(store.pnl()?.summary.trades).toBe(3);
  });

  it('openOrder posts and refreshes the snapshot', () => {
    store.openOrder({ signalId: 7, qty: 50 });
    const req = http.expectOne('/api/v1/paper/orders');
    expect(req.request.body).toEqual({ signalId: 7, qty: 50 });
    req.flush({ id: 2 });
    flushSnapshot(); // loadAll re-fires
  });

  it('close posts to the position close endpoint', () => {
    store.close(1, '131.00');
    const req = http.expectOne('/api/v1/paper/positions/1/close');
    expect(req.request.body).toEqual({ price: '131.00' });
    req.flush({ id: 1, realizedPnl: '550.00' });
    flushSnapshot();
  });

  it('reset posts the confirm flag', () => {
    store.reset(true);
    const req = http.expectOne('/api/v1/paper/reset');
    expect(req.request.body).toEqual({ confirm: true });
    req.flush(null);
    flushSnapshot();
  });

  it('reconnect re-fetches the snapshot', () => {
    ws.reconnects$.next();
    flushSnapshot();
    expect(store.positions()).toHaveLength(1);
  });
});
