/**
 * Loud EOD provenance chip (audit 2026-07-02 §10.2-11 / §11 item 12): end-of-day aggregates often
 * sit beside LIVE tiles on the same screen (sector stats, equity returns, index contribution) and
 * the quiet "as on <date>" text read as decoration — a live +4.47% tile next to a −1.23% EOD card
 * looked contradictory. The warn-toned chip makes the provenance unmissable.
 */
export function EodBadge({ asOf }: { asOf?: string | null }) {
  return (
    <span
      className="inline-flex items-center gap-1 rounded border border-warn/40 bg-warn/10 px-1.5 py-0.5 text-xs font-medium text-warn"
      title="End-of-day data — computed from the last completed session's bhavcopy, not live quotes."
    >
      EOD{asOf ? ` · as of ${asOf}` : ''}
    </span>
  );
}
