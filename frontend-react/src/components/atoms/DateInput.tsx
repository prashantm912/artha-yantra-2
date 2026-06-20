interface DateInputProps {
  value: string | null;
  onChange: (value: string | null) => void;
  ariaLabel: string;
}

/** Native date picker styled with --ay-* tokens. */
export function DateInput({ value, onChange, ariaLabel }: DateInputProps) {
  return (
    <input
      type="date"
      aria-label={ariaLabel}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || null)}
      className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
    />
  );
}
