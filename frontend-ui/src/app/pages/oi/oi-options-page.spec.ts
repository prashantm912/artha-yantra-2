import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiAnalyticsStore } from '../../stores/oi-analytics.store';
import { OiOptionsPage } from './oi-options-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiOptionsPage], template: `<ay-oi-options-page />` })
class Host {}

describe('OiOptionsPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('ay.oi.name', 'NIFTY 50');
    localStorage.setItem('ay.oi.expiry', '2026-06-25');
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

  it('reloads on the shared selection and renders the PCR header + folded chain', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    // background housekeeping from MarketStore + SymbolContextStore
    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    http
      .expectOne((r) => r.url.includes('/options/oi-stats'))
      .flush({ pcr: '1.5000', maxPain: '22500.00', ceOi: 1000, peOi: 1500, asOf: 'x' });
    http
      .expectOne((r) => r.url.includes('/options/active-strikes'))
      .flush({ sentimentPct: '12.50', items: [], asOf: 'x' });
    http
      .expectOne((r) => r.url.includes('/options/oi-analysis'))
      .flush({
        items: [
          {
            bucket: 'b',
            strike: '22500',
            optionType: 'CE',
            ltp: '100.00',
            oi: 1000,
            oiChange: 50,
            iv: '12.5000',
            spot: '22480.00',
          },
          {
            bucket: 'b',
            strike: '22500',
            optionType: 'PE',
            ltp: '90.00',
            oi: 1500,
            oiChange: -20,
            iv: '13.0000',
            spot: '22480.00',
          },
        ],
      });
    await fixture.whenStable();

    const store = TestBed.inject(OiAnalyticsStore);
    expect(store.chainRows()).toHaveLength(1);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('PCR 1.5000');
    expect(text).toContain('1 strike');
  });
});
