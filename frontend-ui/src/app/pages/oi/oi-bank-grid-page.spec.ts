import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiBankGridPage } from './oi-bank-grid-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiBankGridPage], template: `<ay-oi-bank-grid-page />` })
class Host {}

describe('OiBankGridPage', () => {
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

  it('renders the front-future buildup row per bank stock (no symbol query)', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));

    const req = http.expectOne((r) => r.url.includes('/futures/banks-grid'));
    // sector-wide — the request carries NO name param
    expect(req.request.params.has('name')).toBe(false);
    req.flush({
      items: [
        {
          underlying: 'SBIN',
          tradingsymbol: 'SBIN26JUNFUT',
          expiry: '2026-06-25',
          ltp: '820',
          pricePct: '-1.00',
          oi: 4800,
          oiChange: -200,
          oiPct: '-4.00',
          interpretation: 'LONG_UNWINDING',
        },
        {
          underlying: 'HDFCBANK',
          tradingsymbol: 'HDFCBANK26JUNFUT',
          expiry: '2026-06-25',
          ltp: '110',
          pricePct: '10.00',
          oi: 1200,
          oiChange: 200,
          oiPct: '20.00',
          interpretation: 'LONG_BUILDUP',
        },
      ],
      asOf: 'x',
    });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('SBIN26JUNFUT');
    expect(text).toContain('HDFCBANK26JUNFUT');
    expect(text).toContain('+10.00');
  });
});
