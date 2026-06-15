import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiFuturesSpurtPage } from './oi-futures-spurt-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiFuturesSpurtPage], template: `<ay-oi-futures-spurt-page />` })
class Host {}

describe('OiFuturesSpurtPage', () => {
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

  it('renders the per-contract futures spurt buildup', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    http
      .expectOne((r) => r.url.includes('/futures/spurt'))
      .flush({
        items: [
          {
            tradingsymbol: 'NIFTY26JUNFUT',
            ltp: '23950',
            oi: 1200,
            oiChange: 200,
            spurtPct: '20.00',
            interpretation: 'LONG_BUILDUP',
          },
        ],
        asOf: 'x',
      });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('NIFTY26JUNFUT');
  });
});
