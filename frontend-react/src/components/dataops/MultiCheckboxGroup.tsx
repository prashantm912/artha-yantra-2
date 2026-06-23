import { cn } from '../../lib/cn.ts';

interface MultiCheckboxOption {
  value: string;
  label: string;
  hint?: string;
}

export function MultiCheckboxGroup(props: {
  options: MultiCheckboxOption[];
  selected: string[];
  onChange: (next: string[]) => void;
  legend: string;
}) {
  const { options, selected, onChange, legend } = props;

  function toggle(value: string): void {
    const next = selected.includes(value)
      ? selected.filter((v) => v !== value)
      : [...selected, value];
    onChange(next);
  }

  return (
    <fieldset className="space-y-1">
      <legend className="text-xs text-ay-muted">{legend}</legend>
      {options.map((option) => (
        <label
          key={option.value}
          className={cn(
            'flex items-center gap-2 rounded border border-ay-border bg-surface-1 px-2 py-1.5 text-sm text-ay-text',
            'cursor-pointer hover:border-accent',
          )}
        >
          <input
            type="checkbox"
            className="accent-accent"
            checked={selected.includes(option.value)}
            onChange={() => toggle(option.value)}
          />
          <span>{option.label}</span>
          {option.hint ? (
            <span className="text-xs text-ay-muted">{option.hint}</span>
          ) : null}
        </label>
      ))}
    </fieldset>
  );
}
