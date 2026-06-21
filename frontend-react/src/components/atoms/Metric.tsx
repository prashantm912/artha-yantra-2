// A header-strip metric pill (label + value), shared by the option-page headers (Chain / Straddle /
// future pages with a metric strip). Extracted from the inline copies in those pages.
interface MetricProps {
  label: string;
  value: string;
  title?: string;
}

export function Metric({ label, value, title }: MetricProps) {
  return (
    <span
      className="rounded border border-ay-border bg-surface-1 px-2 py-1 text-xs text-ay-text"
      title={title}
    >
      <span className="text-ay-muted">{label} </span>
      <span className="font-semibold tabular-nums">{value}</span>
    </span>
  );
}
