import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { WsClientService } from '../../core/ws-client.service';
import { OiPremiumPage } from './oi-premium-page';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  topic(): Subject<string> {
    return new Subject<string>();
  }
  activate(): void {}
}

@Component({ imports: [OiPremiumPage], template: `<ay-oi-premium-page />` })
class Host {}

describe('OiPremiumPage', () => {
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

  it('renders the ATM straddle and the premium chain', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http.match((r) => r.url.includes('/system/status')).forEach((r) => r.flush({}));
    http.match((r) => r.url.endsWith('/underlyings')).forEach((r) => r.flush([]));
    http.match((r) => r.url.endsWith('/expiries')).forEach((r) => r.flush(['2026-06-25']));

    http
      .expectOne((r) => r.url.includes('/options/premium-series'))
      .flush({
        items: [
          {
            bucket: '2026-06-20T09:15:00+05:30',
            atmStrike: '22500',
            atmStraddle: '150',
            spot: '22480',
          },
          {
            bucket: '2026-06-20T09:20:00+05:30',
            atmStrike: '22500',
            atmStraddle: '170',
            spot: '22520',
          },
        ],
        asOf: 'x',
      });
    http
      .expectOne((r) => r.url.endsWith('/options/premium'))
      .flush({
        items: [{ strike: '22500', straddle: '150.00', ce: '80.00', pe: '70.00' }],
        atmStrike: '22500',
        atmStraddle: '150.00',
        spot: '22480',
        asOf: 'x',
      });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('ATM straddle');
    expect(text).toContain('22500');
  });
});
