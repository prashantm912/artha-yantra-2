interface DateInputProps {
  value: string | null;
  onChange: (value: string | null) => void;
  ariaLabel: string;
  /** Native hover tooltip explaining the control (#16). */
  title?: string;
}

/** Native date picker styled with --ay-* tokens. */
export function DateInput({ value, onChange, ariaLabel, title }: DateInputProps) {
  return (
    <input
      type="date"
      aria-label={ariaLabel}
      title={title}
      value={value ?? ''}
      onChange={(e) => onChange(e.target.value || null)}
      className="h-9 rounded-md border border-ay-border bg-surface-1 px-2 text-sm text-ay-text outline-none focus:border-accent"
    />
  );
}
