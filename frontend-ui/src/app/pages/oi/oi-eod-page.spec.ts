import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiEodPage } from './oi-eod-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiEodPage], template: `<ay-oi-eod-page />` })
class Host {}

describe('OiEodPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('ay.oi.name', 'NIFTY 50');
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

  it('renders the EOD OHLC+OI rollup for the range', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    const req = http.expectOne((r) => r.url.includes('/futures/eod'));
    expect(req.request.params.has('from')).toBe(true);
    req.flush({
      items: [
        {
          tradingsymbol: 'NIFTY26JUNFUT',
          tradeDate: '2026-06-18',
          open: '23900',
          high: '24010',
          low: '23850',
          close: '23980',
          oiClose: 110000,
          oiChange: 1500,
          volume: 90000,
        },
      ],
    });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('NIFTY26JUNFUT');
    expect(text).toContain('2026-06-18');
  });
});
