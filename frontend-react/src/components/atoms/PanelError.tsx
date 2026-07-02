/**
 * Inline error card for cockpit-style panels (audit P1-11): a failed fetch must never render as
 * the calm empty-state copy — "no data for this selection" and "the data source is down" drive
 * OPPOSITE trading reads. Mirrors QueryState's error affordance for panels that compose several
 * queries and cannot wrap each in QueryState wholesale.
 */
export function PanelError({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="p-2 text-sm">
      <span className="text-ay-bear">
        Failed to load — the server returned an error. This is not an empty result.
      </span>
      <button type="button" className="ml-2 text-ay-accent underline" onClick={onRetry}>
        Retry
      </button>
    </div>
  );
}
