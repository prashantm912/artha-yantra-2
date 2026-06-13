import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';
import { signal } from '@angular/core';
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { WsClientService } from '../core/ws-client.service';
import { OptimizationsStore } from './optimizations.store';

class FakeWs {
  readonly state = signal<'idle'>('idle');
  readonly reconnects$ = new Subject<void>();
  readonly frames$ = new Subject<string>();
  topic(): typeof this.frames$ {
    return this.frames$;
  }
  activate(): void {}
}

describe('OptimizationsStore', () => {
  let http: HttpTestingController;
  let ws: FakeWs;
  let store: InstanceType<typeof OptimizationsStore>;

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
    store = TestBed.inject(OptimizationsStore);
  });

  afterEach(() => http.verify());

  it('loadBest passes the sort mode and populates the leaderboard', () => {
    store.loadBest('sweep-1', 'plateau');
    const req = http.expectOne((r) => r.url === '/api/v1/optimizations/sweep-1/best');
    expect(req.request.params.get('sort')).toBe('plateau');
    req.flush({
      metric: 'sharpe',
      sort: 'plateau',
      items: [{ trialNumber: 7, params: {}, objective: 1.8 }],
    });
    expect(store.best().map((b) => b.trialNumber)).toEqual([7]);
    expect(store.metric()).toBe('sharpe');
  });

  it('loadTrials includes pruned/failed trials (flagged, never hidden)', () => {
    store.loadTrials('sweep-1');
    http
      .expectOne((r) => r.url === '/api/v1/optimizations/sweep-1/trials')
      .flush({
        items: [
          { trialNumber: 1, params: {}, objectiveValues: { sharpe: 1.2 }, state: 'COMPLETE' },
          { trialNumber: 2, params: {}, objectiveValues: null, state: 'PRUNED' },
        ],
        limit: 100,
        offset: 0,
      });
    expect(store.trials().map((t) => t.state)).toEqual(['COMPLETE', 'PRUNED']);
  });

  it('promote posts the trial number and yields the new draft version', () => {
    const then = vi.fn();
    store.promote('sweep-1', 7, then);
    const req = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/optimizations/sweep-1/promote',
    );
    expect(req.request.body.trialId).toBe(7);
    req.flush({ strategyId: 's', newVersion: '1.1.0', status: 'draft' });
    expect(then).toHaveBeenCalledWith('1.1.0');
  });

  it('a sweep progress frame updates best-so-far and trial counts', async () => {
    store.loadBest('sweep-1', 'plateau');
    http
      .expectOne((r) => r.url === '/api/v1/optimizations/sweep-1/best')
      .flush({ metric: 'sharpe', items: [] });
    store.loadTrials('sweep-1');
    http
      .expectOne((r) => r.url === '/api/v1/optimizations/sweep-1/trials')
      .flush({ items: [], limit: 100, offset: 0 });

    ws.frames$.next(
      JSON.stringify({
        jobId: 'sweep-1',
        status: 'running',
        progress: 40,
        trialsCompleted: 12,
        trialsTotal: 30,
        bestSoFar: 1.6,
      }),
    );
    await flushed();
    expect(store.bestSoFar()).toBe(1.6);
    expect(store.trialsCompleted()).toBe(12);
    // a frame triggers a debounced reload of trials + best
    http
      .expectOne((r) => r.url === '/api/v1/optimizations/sweep-1/trials')
      .flush({ items: [], limit: 100, offset: 0 });
    http
      .expectOne((r) => r.url === '/api/v1/optimizations/sweep-1/best')
      .flush({ metric: 'sharpe', items: [] });
  });
});

function flushed(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 60));
}
