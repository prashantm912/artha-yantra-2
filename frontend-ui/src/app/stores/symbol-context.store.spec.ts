import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SymbolContextStore } from './symbol-context.store';

describe('SymbolContextStore', () => {
  let http: HttpTestingController;

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  function make(): InstanceType<typeof SymbolContextStore> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(SymbolContextStore);
  }

  it('defaults the expiry to the nearest on load and persists the interval', () => {
    const store = make();
    http.expectOne((r) => r.url.endsWith('/expiries')).flush(['2026-06-25', '2026-07-30']);
    expect(store.expiry()).toBe('2026-06-25');

    store.setInterval('15m');
    expect(store.interval()).toBe('15m');
    expect(localStorage.getItem('ay.oi.interval')).toBe('15m');
    http.verify();
  });

  it('setName clears the expiry, reloads expiries and re-defaults', () => {
    const store = make();
    http.expectOne((r) => r.url.endsWith('/expiries')).flush(['2026-06-25']);

    store.setName('NIFTY BANK');
    expect(store.name()).toBe('NIFTY BANK');
    expect(store.expiry()).toBeNull();
    expect(localStorage.getItem('ay.oi.name')).toBe('NIFTY BANK');

    http
      .expectOne((r) => r.url.includes('NIFTY%20BANK') && r.url.endsWith('/expiries'))
      .flush(['2026-06-26']);
    expect(store.expiry()).toBe('2026-06-26');
    http.verify();
  });

  it('hydrates name/expiry/interval from localStorage without overriding a stored expiry', () => {
    localStorage.setItem('ay.oi.name', 'FINNIFTY');
    localStorage.setItem('ay.oi.expiry', '2026-08-01');
    localStorage.setItem('ay.oi.interval', '5m');
    const store = make();

    http
      .expectOne((r) => r.url.includes('FINNIFTY') && r.url.endsWith('/expiries'))
      .flush(['2026-09-01']);
    expect(store.name()).toBe('FINNIFTY');
    expect(store.expiry()).toBe('2026-08-01');
    expect(store.interval()).toBe('5m');
    http.verify();
  });
});
