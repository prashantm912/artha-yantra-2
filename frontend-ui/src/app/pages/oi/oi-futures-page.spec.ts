import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { OiFuturesPage } from './oi-futures-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiFuturesPage], template: `<ay-oi-futures-page />` })
class Host {}

describe('OiFuturesPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: new FakeWs() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => localStorage.clear());

  it('reloads futures OI on the shared selection', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    http
      .expectOne((r) => r.url.includes('/futures/oi-analysis'))
      .flush({
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
    await fixture.whenStable();

    const store = TestBed.inject(OiAnalyticsStore);
    expect(store.futures()).toHaveLength(1);
    expect(store.futures()[0].tradingsymbol).toBe('NIFTY26JUNFUT');

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('NIFTY26JUNFUT');
  });
});
