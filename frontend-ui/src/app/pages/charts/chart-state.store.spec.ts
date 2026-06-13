import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach } from 'vitest';
import { ChartStateStore } from './chart-state.store';

describe('ChartStateStore (chart-state persistence, Phase 40C)', () => {
  beforeEach(() => localStorage.clear());

  function freshStore(): InstanceType<typeof ChartStateStore> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    return TestBed.inject(ChartStateStore);
  }

  it('persists symbol/interval/overlays and restores them on a fresh load', () => {
    const store = freshStore();
    store.setSymbol('NSE:RELIANCE');
    store.setInterval('5m');
    store.addOverlay('EMA', { period: 50 });

    const reloaded = freshStore();
    expect(reloaded.symbol()).toBe('NSE:RELIANCE');
    expect(reloaded.interval()).toBe('5m');
    expect(reloaded.overlays()).toEqual([{ id: 'EMA', params: { period: 50 } }]);
  });

  it('removeOverlay drops it and updateParam edits in place', () => {
    const store = freshStore();
    store.addOverlay('EMA', { period: 9 });
    store.addOverlay('RSI', { period: 14 });
    store.updateParam('EMA', 'period', 21);
    expect(store.overlays().find((o) => o.id === 'EMA')?.params['period']).toBe(21);
    store.removeOverlay('RSI');
    expect(store.overlays().map((o) => o.id)).toEqual(['EMA']);
  });

  it('does not add a duplicate overlay id', () => {
    const store = freshStore();
    store.addOverlay('EMA', { period: 9 });
    store.addOverlay('EMA', { period: 9 });
    expect(store.overlays()).toHaveLength(1);
  });
});
