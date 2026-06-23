package in.arthayantra.marketdata.upstox;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window rate limiter for Upstox's per-token caps, shared across the backfill workers. Upstox
 * enforces 50 req/s, 500 req/min AND <b>2000 req / 30 min</b> — the 30-minute window is the binding
 * constraint for a long historical pull, and ignoring it (a per-call throttle only paces the second)
 * is what exhausted the quota and 429'd. Conservative margins (45 / 450 / 1800), the same three-window
 * model marketcalls/ExpiryTrack uses. {@link #acquire()} blocks until a request fits every window.
 */
final class UpstoxRateLimiter {

  private record Window(int max, long durationMs, Deque<Long> hits) {}

  private final Window[] windows = {
    new Window(45, 1_000L, new ArrayDeque<>()),
    new Window(450, 60_000L, new ArrayDeque<>()),
    new Window(1_800, 1_800_000L, new ArrayDeque<>())
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
}
