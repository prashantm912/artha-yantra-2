package in.arthayantra.marketdata.kite;

/**
 * Re-arm the live ticker after a session change (B-2). Implemented by the feed pipeline and
 * referenced from the kite session wiring through this seam so the {@code kite} module never
 * structurally depends on {@code feed} — a direct reference would close a
 * {@code feed → instruments → kite → feed} Modulith cycle.
 */
public interface FeedRearm {

  /** Rebuild the ticker handle with the currently-stored token; no-op unless the feed is running. */
  void restartFeed();
}
