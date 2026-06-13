import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import { BacktestsStore } from './backtests.store';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  readonly frames$ = new Subject<string>();
  topic(): typeof this.frames$ {
    return this.frames$;
  }
  activate(): void {}
}

describe('BacktestsStore (runner slice)', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof BacktestsStore>;

  beforeEach(() => {
    ws = new FakeWs();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: ws },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(BacktestsStore);
  });

  afterEach(() => http.verify());

  function runQuick(): void {
    store.runQuick({
      strategyId: 's1',
      from: '2026-01-01T00:00:00+05:30',
      to: '2026-03-01T00:00:00+05:30',
    });
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/backtests/run')
      .flush({ jobId: 'job-1', status: 'queued' });
  }

  it('runQuick fires the run and tracks the returned jobId', () => {
    runQuick();
    expect(store.runningJobId()).toBe('job-1');
    expect(store.runStatus()).toBe('queued');
  });

  it('progress frames for the running job update progress', async () => {
    runQuick();
    ws.frames$.next(JSON.stringify({ jobId: 'job-1', status: 'running', progress: 60 }));
    await flushed();
    expect(store.runProgress()).toBe(60);
    expect(store.runStatus()).toBe('running');
  });

  it('a completed frame fetches the run results via resultRef', async () => {
    runQuick();
    ws.frames$.next(JSON.stringify({ jobId: 'job-1', status: 'completed', progress: 100 }));
    await flushed();
    http
      .expectOne((r) => r.url === '/api/v1/backtests/jobs/job-1')
      .flush({ jobId: 'job-1', status: 'completed', resultRef: 'run-9' });
    http
      .expectOne((r) => r.url === '/api/v1/backtests/run-9/results')
      .flush({ metrics: { sharpe: '1.42', tradeCount: 12 }, equityCurve: [], dataHash: 'h' });
    expect(store.results()?.metrics['sharpe']).toBe('1.42');
  });

  it('ignores frames for other jobs', async () => {
    runQuick();
    ws.frames$.next(JSON.stringify({ jobId: 'other', status: 'completed', progress: 100 }));
    await flushed();
    expect(store.runProgress()).toBe(0);
  });
});

function flushed(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 40));
}
