import { afterEach, describe, expect, it } from 'vitest';
import {
  defaultState,
  loadState,
  paneCount,
  saveState,
  setLayout,
  setPaneWidget,
  type WorkspaceState,
} from './multipleWindow.ts';

afterEach(() => localStorage.clear());

describe('multipleWindow', () => {
  it('maps each layout to its active pane count', () => {
    expect(paneCount('1')).toBe(1);
    expect(paneCount('2')).toBe(2);
    expect(paneCount('4')).toBe(4);
  });

  it('defaults to a 2-pane workspace with 4 pane slots', () => {
    const s = defaultState();
    expect(s.layout).toBe('2');
    expect(s.panes).toHaveLength(4);
  });

  it('sets a pane widget without touching the others (lossless across slots)', () => {
    const s = setPaneWidget(defaultState(), 2, 'world');
    expect(s.panes[2]).toBe('world');
    expect(s.panes[0]).toBe('vix'); // unchanged
  });

  it('ignores out-of-range pane indices', () => {
    const s = defaultState();
    expect(setPaneWidget(s, 9, 'world')).toEqual(s);
    expect(setPaneWidget(s, -1, 'world')).toEqual(s);
  });

  it('changes layout while preserving pane selections', () => {
    const s = setLayout(defaultState(), '4');
    expect(s.layout).toBe('4');
    expect(s.panes).toEqual(defaultState().panes);
  });

  it('round-trips through localStorage', () => {
    const s = setPaneWidget(setLayout(defaultState(), '4'), 3, 'risk-calc');
    saveState(s);
    expect(loadState()).toEqual(s);
  });

  it('falls back to the default on absent or corrupt storage', () => {
    expect(loadState()).toEqual(defaultState()); // absent
    localStorage.setItem('ay-multi-window', '{not json');
    expect(loadState()).toEqual(defaultState()); // corrupt
    localStorage.setItem('ay-multi-window', JSON.stringify({ layout: '9', panes: [] }));
    expect(loadState()).toEqual(defaultState()); // invalid shape
  });

  it('rejects a stored widget id that is not in the registry', () => {
    const bad: unknown = { layout: '2', panes: ['vix', 'bogus-widget', '', ''] };
    localStorage.setItem('ay-multi-window', JSON.stringify(bad as WorkspaceState));
    expect(loadState()).toEqual(defaultState());
  });
});
