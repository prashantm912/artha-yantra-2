import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { FiiDiiPage } from './fii-dii-page';

@Component({ imports: [FiiDiiPage], template: `<ay-fii-dii-page />` })
class Host {}

describe('FiiDiiPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('renders the cash + long-short tables on the selected date', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

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
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('FII/FPI');
  });
});
