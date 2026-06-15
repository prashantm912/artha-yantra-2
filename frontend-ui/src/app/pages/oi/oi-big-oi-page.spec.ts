import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiBigOiPage } from './oi-big-oi-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiBigOiPage], template: `<ay-oi-big-oi-page />` })
class Host {}

describe('OiBigOiPage', () => {
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

  it('renders the big-oi movers, premium and trend', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    http
      .expectOne((r) => r.url.includes('/options/big-oi'))
      .flush({
        items: [{ strike: '22500', optionType: 'PE', oi: 2000, oiChange: -900, ltp: '80' }],
        asOf: 'x',
      });
    http
      .expectOne((r) => r.url.includes('/options/premium'))
      .flush({ items: [], atmStrike: '22500', atmStraddle: '150.00', spot: '22480', asOf: 'x' });
    http
      .expectOne((r) => r.url.includes('/options/trending'))
      .flush({
        items: [{ bucket: 'b', totalOi: 1500, ceOi: 1000, peOi: 500, trend: 'UP' }],
        asOf: 'x',
      });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ATM straddle');
    expect(text).toContain('22500');
  });
});
