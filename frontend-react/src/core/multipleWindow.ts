// Multiple Window (oipulse "Multiple Window") — a composable monitoring workspace: a 1 / 2 / 2x2 grid
// of panes, each showing a chosen widget, with the layout + per-pane selection persisted to
// localStorage. This module is the pure state + persistence layer (no React); the page is a thin shell
// over it. Widgets are referenced by a stable id; the page maps each id to a lazily-loaded component.

export type Layout = '1' | '2' | '4';

/** The pane-able widgets (id is persisted; the page maps id -> a lazy component). */
export const WIDGETS = [
  { id: 'vix', label: 'Vix & Index' },
  { id: 'world', label: 'World Indices' },
  { id: 'oi-stats', label: 'OI Statistics' },
  { id: 'oi-spurt', label: 'OI Spurt' },
  { id: 'big-oi', label: 'Big OI Movement' },
  { id: 'connecting-dots', label: 'Connecting Dots' },
  { id: 'holidays', label: 'Market Holidays' },
  { id: 'risk-calc', label: 'Risk Calculator' },
] as const;

export type WidgetId = (typeof WIDGETS)[number]['id'] | '';

export interface WorkspaceState {
  layout: Layout;
  /** Always length 4; entries beyond the active pane count are kept so toggling layout is lossless. */
  panes: WidgetId[];
}

const STORAGE_KEY = 'ay-multi-window';
const VALID_IDS = new Set<string>([...WIDGETS.map((w) => w.id), '']);
const VALID_LAYOUTS = new Set<Layout>(['1', '2', '4']);

/** Active panes for a layout: 1, 2 or 4. */
export function paneCount(layout: Layout): number {
  return layout === '1' ? 1 : layout === '2' ? 2 : 4;
}

export function defaultState(): WorkspaceState {
  return { layout: '2', panes: ['vix', 'oi-spurt', '', ''] };
}

/** True when an arbitrary value is a well-formed, in-registry workspace state. */
function isValid(v: unknown): v is WorkspaceState {
  if (!v || typeof v !== 'object') return false;
  const s = v as Record<string, unknown>;
  if (!VALID_LAYOUTS.has(s.layout as Layout)) return false;
  if (!Array.isArray(s.panes) || s.panes.length !== 4) return false;
  return s.panes.every((p) => typeof p === 'string' && VALID_IDS.has(p));
}

/** Loads the persisted workspace, falling back to the default on absent/corrupt/stale data. */
export function loadState(): WorkspaceState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaultState();
    const parsed: unknown = JSON.parse(raw);
    return isValid(parsed) ? parsed : defaultState();
  } catch {
    return defaultState();
  }
}

export function saveState(state: WorkspaceState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // ignore quota/availability errors — persistence is best-effort
  }
}

export function setLayout(state: WorkspaceState, layout: Layout): WorkspaceState {
  return { ...state, layout };
}

export function setPaneWidget(state: WorkspaceState, index: number, widget: WidgetId): WorkspaceState {
  if (index < 0 || index > 3) return state;
  const panes = state.panes.slice() as WidgetId[];
  panes[index] = widget;
  return { ...state, panes };
}
