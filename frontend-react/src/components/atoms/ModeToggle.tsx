import type { OiMode } from '../../stores/symbolContext.store.ts';

interface ModeToggleProps {
  mode: OiMode;
  onChange: (mode: OiMode) => void;
  /** Native hover tooltip explaining the control (#16). */
  title?: string;
}

/** Live/History toggle — aria-pressed carries state; the label is the non-colour cue. */
export function ModeToggle({ mode, onChange, title }: ModeToggleProps) {
  const history = mode === 'history';
  return (
    <button
      type="button"
      aria-label="Toggle live/history mode"
      title={title ?? 'Switch between the live intraday feed and a past session'}
      aria-pressed={history}
      onClick={() => onChange(history ? 'live' : 'history')}
      className="h-9 rounded-md border border-ay-border bg-surface-1 px-3 text-sm text-ay-text hover:border-accent"
    >
      {history ? 'History' : 'Live'}
    </button>
  );
}
