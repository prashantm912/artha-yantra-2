import type { KeyboardEvent } from 'react';
import { cn } from '../../lib/cn.ts';

export function SqlEditor(props: { value: string; onChange: (v: string) => void; onRun: () => void; placeholder?: string }) {
  const { value, onChange, onRun, placeholder } = props;

  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      onRun();
    }
  }

  return (
    <div>
      <label htmlFor="sql-editor" className="mb-1 block text-xs font-semibold text-ay-text">
        SQL query
      </label>
      <textarea
        id="sql-editor"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        rows={8}
        spellCheck={false}
        className={cn(
          'w-full rounded border border-ay-border bg-surface-2 p-2 font-mono text-xs text-ay-text',
          'focus:outline-none focus:ring-1 focus:ring-accent',
        )}
      />
      <p className="mt-1 text-xs text-ay-muted">⌘/Ctrl + Enter to run</p>
    </div>
  );
}
