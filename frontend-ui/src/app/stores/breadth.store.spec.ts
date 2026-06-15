import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { BreadthStore } from './breadth.store';

describe('BreadthStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof BreadthStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(BreadthStore);
  });

  afterEach(() => http.verify());

  it('load maps the breadth summary + delivery leaders', () => {
    store.setDate('2026-06-12');
    store.load();
    http
      .expectOne((r) => r.url.includes('/market/breadth'))
      .flush({
        summary: {
          tradeDate: '2026-06-12',
          advances: 2,
          declines: 1,
          unchanged: 0,
          total: 3,
          avgDeliveryPct: '51.83',
        },
        topDelivery: [{ symbol: 'TCS', deliveryPct: '60.00', close: '210', pctChange: '5.00' }],
        asOf: 'x',
      });
    expect(store.breadth()?.summary.advances).toBe(2);
    expect(store.breadth()?.topDelivery[0].symbol).toBe('TCS');
    expect(store.loading()).toBe(false);
  });

  it('maps a 422 DATA_GAP to null without throwing', () => {
    store.setDate('1999-01-04');
    store.load();
    http
      .expectOne((r) => r.url.includes('/market/breadth'))
      .flush({ code: 'DATA_GAP' }, { status: 422, statusText: 'Unprocessable Entity' });
    expect(store.breadth()).toBeNull();
  });
});
