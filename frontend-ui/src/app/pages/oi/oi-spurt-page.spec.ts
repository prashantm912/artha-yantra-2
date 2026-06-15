import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiSpurtPage } from './oi-spurt-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiSpurtPage], template: `<ay-oi-spurt-page />` })
class Host {}

describe('OiSpurtPage', () => {
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

  it('renders the per-strike spurt grid + the OI-bias badge', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    http
      .expectOne((r) => r.url.includes('/options/spurt'))
      .flush({
        items: [
          {
            strike: '22500',
            optionType: 'CE',
            ltp: '110',
            oi: 1200,
            oiChange: 200,
            spurtPct: '20.00',
            interpretation: 'LONG_BUILDUP',
          },
        ],
        summary: { interpretation: 'LONG_BUILDUP', spotDelta: '20', oiChange: 200 },
        asOf: 'x',
      });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Long Buildup');
  });
});
