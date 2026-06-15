import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { BreadthPage } from './breadth-page';

@Component({ imports: [BreadthPage], template: `<ay-breadth-page />` })
class Host {}

describe('BreadthPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  it('renders the advance/decline summary + delivery leaders', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();

    http
      .expectOne((r) => r.url.includes('/market/breadth'))
      .flush({
        summary: {
          tradeDate: '2026-06-12',
          advances: 2,
          declines: 1,
          unchanged: 0,
          total: 3,
          avgDeliveryPct: '51.83',
        },
        topDelivery: [{ symbol: 'TCS', deliveryPct: '60.00', close: '210', pctChange: '5.00' }],
        asOf: 'x',
      });
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('2 advances');
    expect(text).toContain('TCS');
  });
});
