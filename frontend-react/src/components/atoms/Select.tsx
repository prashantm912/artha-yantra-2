import { cn } from '../../lib/cn.ts';

export type SelectOption = { value: string; label: string } | string;

interface SelectProps {
  value: string | null;
  options: SelectOption[];
  onChange: (value: string) => void;
  ariaLabel: string;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
}

function normalize(o: SelectOption): { value: string; label: string } {
  return typeof o === 'string' ? { value: o, label: o } : o;
}

/** Native select styled with --ay-* tokens (a11y-strong, zero-dep). Shared control-bar atom. */
export function Select({
  value,
  options,
  onChange,
  ariaLabel,
  placeholder,
  disabled,
  className,
}: SelectProps) {
  return (
    <select
      aria-label={ariaLabel}
      disabled={disabled}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value)}
      className={cn(
        'h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text',
        'outline-none focus:border-accent disabled:opacity-50',
        className,
      )}
    >
      {(placeholder || value === null) && (
        <option value="" disabled>
          {placeholder ?? 'Select…'}
        </option>
      )}
      {options.map(normalize).map((o) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  );
}
