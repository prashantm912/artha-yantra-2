// Server-offset pager for newest-first feeds (signals / rejections — audit §6: the 200-row page
// cap was silent). "Newer/Older" instead of Prev/Next because rows sort by generated_at DESC.
// Renders nothing on a single short page so existing screens keep their footprint.

interface PagerProps {
  offset: number;
  limit: number;
  /** Rows in the CURRENT page — a full page (count === limit) implies more may follow. */
  count: number;
  onOffset: (next: number) => void;
}

export function Pager({ offset, limit, count, onOffset }: PagerProps) {
  const mayHaveOlder = count === limit;
  if (offset === 0 && !mayHaveOlder) return null;
  return (
    <nav className="mt-2 flex items-center gap-2 text-xs text-ay-muted" aria-label="Pagination">
      <button
        type="button"
        disabled={offset === 0}
        onClick={() => onOffset(Math.max(0, offset - limit))}
        title="Show the previous (newer) page"
        className="h-7 rounded-md border border-ay-border px-2 text-ay-text hover:border-accent disabled:opacity-40"
      >
        ← Newer
      </button>
      <span className="tabular-nums">
        rows {count === 0 ? 0 : offset + 1}–{offset + count}
      </span>
      <button
        type="button"
        disabled={!mayHaveOlder}
        onClick={() => onOffset(offset + limit)}
        title="Show the next (older) page"
        className="h-7 rounded-md border border-ay-border px-2 text-ay-text hover:border-accent disabled:opacity-40"
      >
        Older →
      </button>
    </nav>
  );
}
