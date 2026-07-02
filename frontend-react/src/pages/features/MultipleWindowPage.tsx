import {
  Component,
  Suspense,
  lazy,
  useEffect,
  useState,
  type ComponentType,
  type ReactNode,
} from 'react';
import { PageHeader } from '../../components/PageHeader.tsx';
import { CompactPaneContext } from '../../components/paneContext.ts';
import { cn } from '../../lib/cn.ts';
import {
  WIDGETS,
  defaultState,
  loadState,
  paneCount,
  saveState,
  setLayout,
  setPaneWidget,
  type Layout,
  type WidgetId,
  type WorkspaceState,
} from '../../core/multipleWindow.ts';

// Multiple Window (§features/multiple-window — oipulse "Multiple Window"). A composable monitoring
// workspace: pick a 1 / 2 / 2x2 layout and drop a different page-widget into each pane to watch several
// views at once. The layout + per-pane selection persist to localStorage (core/multipleWindow), so the
// workspace is restored on reload. Each pane is an isolated lazy-loaded existing page — a failure in one
// pane is caught locally and never takes down the others.

const WIDGET_COMPONENTS: Record<string, ComponentType> = {
  vix: lazy(() => import('./VixIndexPage.tsx').then((m) => ({ default: m.VixIndexPage }))),
  world: lazy(() => import('./WorldIndicesPage.tsx').then((m) => ({ default: m.WorldIndicesPage }))),
  'oi-stats': lazy(() =>
    import('../options/OiStatisticsPage.tsx').then((m) => ({ default: m.OiStatisticsPage })),
  ),
  'oi-spurt': lazy(() =>
    import('../options/OptionsSpurtPage.tsx').then((m) => ({ default: m.OptionsSpurtPage })),
  ),
  'big-oi': lazy(() =>
    import('../options/BigOiMovementPage.tsx').then((m) => ({ default: m.BigOiMovementPage })),
  ),
  'connecting-dots': lazy(() =>
    import('../options/ConnectingDotsPage.tsx').then((m) => ({ default: m.ConnectingDotsPage })),
  ),
  holidays: lazy(() =>
    import('./MarketHolidaysPage.tsx').then((m) => ({ default: m.MarketHolidaysPage })),
  ),
  'risk-calc': lazy(() =>
    import('./RiskCalculatorPage.tsx').then((m) => ({ default: m.RiskCalculatorPage })),
  ),
};

/** Catches a render error inside a single pane so the other panes keep working. */
class PaneBoundary extends Component<{ resetKey: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidUpdate(prev: { resetKey: string }) {
    if (prev.resetKey !== this.props.resetKey && this.state.failed) {
      this.setState({ failed: false }); // re-try when the pane's widget changes
    }
  }
  render() {
    if (this.state.failed) {
      return <p className="p-4 text-sm text-bear">This widget failed to load.</p>;
    }
    return this.props.children;
  }
}

const LAYOUTS: { id: Layout; label: string }[] = [
  { id: '1', label: 'Single' },
  { id: '2', label: 'Two' },
  { id: '4', label: 'Grid' },
];

const selectCls =
  'h-7 rounded border border-ay-border bg-surface-1 px-1.5 text-xs text-ay-text';

function Pane({
  widget,
  onChange,
}: {
  widget: WidgetId;
  onChange: (id: WidgetId) => void;
}) {
  const Widget = widget ? WIDGET_COMPONENTS[widget] : undefined;
  // The pane chrome carries the title; the embedded page's hero header collapses (CompactPaneContext).
  const label = WIDGETS.find((w) => w.id === widget)?.label ?? 'Pane';
  return (
    <section className="flex min-h-0 flex-col rounded-md border border-ay-border bg-surface-0">
      <header className="flex items-center justify-between gap-2 border-b border-ay-border px-2 py-1.5">
        <span className="truncate text-xs font-semibold text-ay-muted">{label}</span>
        <select
          value={widget}
          onChange={(e) => onChange(e.target.value as WidgetId)}
          aria-label="Pane widget"
          title="Choose which view this pane shows."
          className={selectCls}
        >
          <option value="">— empty —</option>
          {WIDGETS.map((w) => (
            <option key={w.id} value={w.id}>
              {w.label}
            </option>
          ))}
        </select>
      </header>
      <div className="min-h-0 flex-1 overflow-auto p-3">
        {Widget ? (
          <PaneBoundary resetKey={widget}>
            <Suspense fallback={<p className="text-sm text-ay-muted">Loading…</p>}>
              <CompactPaneContext.Provider value={true}>
                <Widget />
              </CompactPaneContext.Provider>
            </Suspense>
          </PaneBoundary>
        ) : (
          <p className="grid h-full place-items-center text-sm text-ay-muted">
            Pick a widget for this pane.
          </p>
        )}
      </div>
    </section>
  );
}

export function MultipleWindowPage() {
  const [state, setState] = useState<WorkspaceState>(() => loadState());

  useEffect(() => {
    saveState(state);
  }, [state]);

  const count = paneCount(state.layout);
  const gridCls =
    state.layout === '1'
      ? 'grid-cols-1'
      : state.layout === '2'
        ? 'grid-cols-1 md:grid-cols-2'
        : 'grid-cols-1 md:grid-cols-2 md:grid-rows-2';

  return (
    <div className="flex h-[calc(100vh-7rem)] flex-col">
      <PageHeader
        title="Multiple Window"
        subtitle="A composable monitoring workspace — watch several views side by side, layout persisted"
        help="Choose a single, two-pane or 2x2 grid layout, then pick a view for each pane to monitor several pages at once. Your layout and the per-pane choices are saved in this browser and restored on reload."
      />

      <div className="mb-2 flex items-center gap-2">
        <span className="text-xs text-ay-muted">Layout</span>
        <div role="tablist" aria-label="Layout" className="flex gap-1">
          {LAYOUTS.map((l) => (
            <button
              key={l.id}
              type="button"
              role="tab"
              aria-selected={state.layout === l.id}
              onClick={() => setState((s) => setLayout(s, l.id))}
              className={cn(
                'h-7 rounded border px-2 text-xs',
                state.layout === l.id
                  ? 'border-accent bg-accent text-surface-0'
                  : 'border-ay-border bg-surface-1 text-ay-text',
              )}
            >
              {l.label}
            </button>
          ))}
        </div>
        <button
          type="button"
          onClick={() => setState(defaultState())}
          className="ml-auto h-7 rounded border border-ay-border bg-surface-1 px-2 text-xs text-ay-muted"
          title="Reset the layout and pane selections to the default."
        >
          Reset
        </button>
      </div>

      <div className={cn('grid min-h-0 flex-1 gap-2', gridCls)}>
        {Array.from({ length: count }, (_, i) => (
          <Pane
            key={i}
            widget={state.panes[i]}
            onChange={(id) => setState((s) => setPaneWidget(s, i, id))}
          />
        ))}
      </div>
    </div>
  );
}
