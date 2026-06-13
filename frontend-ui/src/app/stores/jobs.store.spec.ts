import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import { JobsStore, type JobDto } from './jobs.store';

/** Fake WS client: hand-driven topic + reconnect streams. */
class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  readonly frames$ = new Subject<string>();
  topic(): typeof this.frames$ {
    return this.frames$;
  }
  activate(): void {}
}

function job(id: string, overrides: Partial<JobDto> = {}): JobDto {
  return {
    jobId: id,
    kind: 'BACKTEST',
    status: 'running',
    progress: 10,
    createdAt: '2026-06-13T10:00:00+05:30',
    ...overrides,
  };
}

describe('JobsStore', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof JobsStore>;

  beforeEach(async () => {
    ws = new FakeWs();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: WsClientService, useValue: ws },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(JobsStore);
    http
      .expectOne((req) => req.url === '/api/v1/backtests/jobs')
      .flush({ items: [job('a', { progress: 10 })], limit: 50, offset: 0 });
    await framesFlushed();
  });

  afterEach(() => http.verify());

  it('seeds the job list from the REST snapshot', () => {
    expect(store.jobs().map((j) => j.jobId)).toEqual(['a']);
  });

  it('a progress frame updates a known job in place without any HTTP', async () => {
    ws.frames$.next(JSON.stringify({ jobId: 'a', status: 'running', progress: 55 }));
    await framesFlushed();
    expect(store.jobs()[0].progress).toBe(55);
    // no extra GET: http.verify() in afterEach asserts the queue is empty
  });

  it('a progress frame for an unknown job triggers one reload to heal the list', async () => {
    ws.frames$.next(JSON.stringify({ jobId: 'b', status: 'queued', progress: 0 }));
    await framesFlushed();
    http
      .expectOne((req) => req.url === '/api/v1/backtests/jobs')
      .flush({
        items: [job('a'), job('b', { status: 'queued', progress: 0 })],
        limit: 50,
        offset: 0,
      });
    expect(store.jobs().map((j) => j.jobId)).toEqual(['a', 'b']);
  });

  it('cancel on a running job (202) marks it cancelling', () => {
    store.cancel('a');
    http
      .expectOne((req) => req.method === 'DELETE' && req.url === '/api/v1/backtests/jobs/a')
      .flush({ status: 'cancelling' }, { status: 202, statusText: 'Accepted' });
    expect(store.jobs()[0].status).toBe('cancelling');
  });

  it('cancel on a queued job (204) marks it cancelled', () => {
    store.cancel('a');
    http
      .expectOne((req) => req.method === 'DELETE' && req.url === '/api/v1/backtests/jobs/a')
      .flush(null, { status: 204, statusText: 'No Content' });
    expect(store.jobs()[0].status).toBe('cancelled');
  });

  it('reconnect re-fetches the snapshot (at-least-once display)', () => {
    ws.reconnects$.next();
    http
      .expectOne((req) => req.url === '/api/v1/backtests/jobs')
      .flush({ items: [job('z')], limit: 50, offset: 0 });
    expect(store.jobs().map((j) => j.jobId)).toEqual(['z']);
  });

  it('active() are the queued/running/cancelling jobs only', async () => {
    ws.frames$.next(JSON.stringify({ jobId: 'a', status: 'completed', progress: 100 }));
    await framesFlushed();
    expect(store.active().length).toBe(0);
  });

  it('unparseable frames are dropped silently', async () => {
    ws.frames$.next('{not json');
    await framesFlushed();
    expect(store.jobs()[0].progress).toBe(10);
  });
});

/** rAF under jsdom falls back to the 16 ms setTimeout path in ConflationBuffer. */
function framesFlushed(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 40));
}
