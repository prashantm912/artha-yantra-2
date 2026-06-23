package in.arthayantra.marketdata.upstox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Sliding-window rate limiter for Upstox's per-token caps, shared across the backfill workers. Upstox
 * enforces 50 req/s, 500 req/min AND <b>2000 req / 30 min</b> — the 30-minute window is the binding
 * constraint for a long historical pull, and ignoring it (a per-call throttle only paces the second)
 * is what exhausted the quota and 429'd. Conservative margins (45 / 450 / 1800), the same three-window
 * model marketcalls/ExpiryTrack uses. {@link #acquire()} blocks until a request fits every window.
 */
public final class UpstoxRateLimiter {

  private record Window(String label, int max, long durationMs, Deque<Long> hits) {}

  /** Per-window quota usage — the read model for the B4 quota widget. */
  public record WindowStat(String window, int used, int max, int remaining) {}

  private final Window[] windows = {
    new Window("1s", 45, 1_000L, new ArrayDeque<>()),
    new Window("1m", 450, 60_000L, new ArrayDeque<>()),
    new Window("30m", 1_800, 1_800_000L, new ArrayDeque<>())
  };

  /** Blocks until a request fits ALL windows, then records its timestamp in each. Thread-safe. */
  void acquire() {
    while (true) {
      long wait;
      synchronized (this) {
        long now = System.currentTimeMillis();
        wait = 0;
        for (Window w : windows) {
          while (!w.hits().isEmpty() && now - w.hits().peekFirst() >= w.durationMs()) {
            w.hits().pollFirst();
          }
          if (w.hits().size() >= w.max()) {
            wait = Math.max(wait, w.durationMs() - (now - w.hits().peekFirst()) + 5);
          }
        }
        if (wait <= 0) {
          for (Window w : windows) {
            w.hits().addLast(now);
          }
          return;
        }
      }
      try {
        Thread.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while rate-limiting", e);
      }
    }
  }

  /**
   * Current per-window usage (used / max / remaining) after pruning expired hits — a read-only
   * snapshot for the B4 quota widget. Thread-safe (shares the {@link #acquire} monitor).
   */
  public synchronized List<WindowStat> getUsageStats() {
    long now = System.currentTimeMillis();
    List<WindowStat> out = new ArrayList<>(windows.length);
    for (Window w : windows) {
      while (!w.hits().isEmpty() && now - w.hits().peekFirst() >= w.durationMs()) {
        w.hits().pollFirst();
      }
      int used = w.hits().size();
      out.add(new WindowStat(w.label(), used, w.max(), Math.max(0, w.max() - used)));
    }
    return out;
  }
}
