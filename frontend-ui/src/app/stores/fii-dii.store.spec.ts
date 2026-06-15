import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { FiiDiiStore } from './fii-dii.store';

describe('FiiDiiStore', () => {
  let http: HttpTestingController;
  let store: InstanceType<typeof FiiDiiStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(FiiDiiStore);
  });

  afterEach(() => http.verify());

  it('load fetches cash, participant-oi and long-short for the range', () => {
    store.setFrom('2026-06-12');
    store.load();

    http
      .expectOne((r) => r.url.includes('/fii-dii/cash'))
      .flush({
        items: [
          {
            tradeDate: '2026-06-12',
            category: 'FII/FPI',
            buyValue: '1000.50',
            sellValue: '800.25',
            netValue: '200.25',
          },
        ],
      });
    http.expectOne((r) => r.url.includes('/fii-dii/participant-oi')).flush({ items: [] });
    http
      .expectOne((r) => r.url.includes('/fii-dii/long-short'))
      .flush({
        items: [{ tradeDate: '2026-06-12', fiiLong: 2000, fiiShort: 1000, ratio: '2.0000' }],
      });

    expect(store.cash()[0].netValue).toBe('200.25');
    expect(store.longShort()[0].ratio).toBe('2.0000');
    expect(store.loading()).toBe(false);
  });

  it('passes the to param only when set', () => {
    store.setFrom('2026-06-10');
    store.setTo('2026-06-12');
    store.load();
    const req = http.expectOne((r) => r.url.includes('/fii-dii/cash'));
    expect(req.request.params.get('to')).toBe('2026-06-12');
    req.flush({ items: [] });
    http.expectOne((r) => r.url.includes('/fii-dii/participant-oi')).flush({ items: [] });
    http.expectOne((r) => r.url.includes('/fii-dii/long-short')).flush({ items: [] });
  });
});
