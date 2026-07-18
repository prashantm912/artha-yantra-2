package in.arthayantra.marketdata.upstox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Token-scoped sliding-window rate limiter for the Upstox analytics token's per-token caps. Upstox
 * enforces 50 req/s, 500 req/min AND <b>2000 req / 30 min</b> — the 30-minute window is the binding
 * constraint for a long historical pull, and ignoring it (a per-call throttle only paces the second)
 * is what exhausted the quota and 429'd. Conservative margins (45 / 450 / 1800), the same three-window
 * model marketcalls/ExpiryTrack uses.
 *
 * <p><b>ONE budget per token (EXT-02).</b> A single instance of this class is a Spring bean shared by
 * <em>every</em> client on the analytics token — the backfill walker AND the live capture / quote /
 * margin / page clients — so the whole token draws from the ONE 2000/30min budget. (Before EXT-02 each
 * of three clients built its own instance and three more were unmetered, so the single token's budget
 * was fragmented ~3× while the live path was unthrottled.)
 *
 * <p><b>Two priorities so a backfill can never starve the live path.</b> A heavy expired-contract
 * backfill hammers the limiter walking a year of contracts; a naïve single budget would let it fill
 * the 30-min window and park the live OI-capture / quote / margin calls in {@link #acquire()} for up
 * to 30 minutes — and a missed live OI-capture interval is forward-capture-only and irreplaceable.
 * So the batch path ({@link #acquireForBatch()}) may only fill each window up to a REDUCED ceiling that
 * leaves {@link #DEFAULT_LIVE_RESERVE_FRACTION} of every window reserved for the live path; the live
 * path ({@link #acquire()}) may use the FULL cap. Both record into the same windows (one honest
 * per-token count) — only the ceiling each path checks differs, so the reserved headroom is always
 * available to a live call no matter how saturated the backfill is.
 */
public final class UpstoxRateLimiter {

  /**
   * Fraction of every window reserved for the live-critical path (option-chain / quote / margin /
   * page reads). The batch backfill may consume only {@code 1 - fraction} of each window, so a live
   * call always has this much headroom. 0.20 of the binding 30-min window is 360 slots — comfortably
   * above the live cadence (5-min chain snapshots + 30-s broadcasts + 3-min futures-OI + quote
   * batches), while the backfill still keeps 1440/30min (a background year-walk tolerates ~20% slower).
   */
  static final double DEFAULT_LIVE_RESERVE_FRACTION = 0.20;

  private record Window(String label, int max, long durationMs, Deque<Long> hits) {}

  /** Per-window quota usage — the read model for the B4 quota widget. */
  public record WindowStat(String window, int used, int max, int remaining) {}

  private final Window[] windows;
  /** Per-window ceiling the batch path may fill to ({@code max - reserve}); the live path uses {@code max}. */
  private final int[] batchCeilings;

  /** Production limiter: 45/s · 450/min · 1800/30min sliding windows, 20% of each reserved for live. */
  public UpstoxRateLimiter() {
    this(
        DEFAULT_LIVE_RESERVE_FRACTION,
        new String[] {"1s", "1m", "30m"},
        new long[] {1_000L, 60_000L, 1_800_000L},
        new int[] {45, 450, 1_800});
  }

  /** Test/tuning constructor — explicit windows + the live-reserve fraction. */
  UpstoxRateLimiter(double liveReserveFraction, String[] labels, long[] durationsMs, int[] maxes) {
    this.windows = new Window[labels.length];
    this.batchCeilings = new int[labels.length];
    for (int i = 0; i < labels.length; i++) {
      windows[i] = new Window(labels[i], maxes[i], durationsMs[i], new ArrayDeque<>());
      batchCeilings[i] = (int) Math.floor(maxes[i] * (1.0 - liveReserveFraction));
    }
  }

  /**
   * Live-critical path — blocks until a request fits ALL windows at their FULL token cap, then records
   * its timestamp in each. The reserved headroom is available here, so a saturated backfill never
   * parks a live capture / quote / margin call. Thread-safe.
   */
  public void acquire() {
    awaitAndRecord(false);
  }

  /**
   * Batch/backfill path — blocks until a request fits every window BELOW the live-reserved ceiling,
   * then records its timestamp in each. A heavy backfill therefore can never consume the slots
   * reserved for the live path (it parks here while live still proceeds). Thread-safe.
   */
  public void acquireForBatch() {
    awaitAndRecord(true);
  }

  private void awaitAndRecord(boolean batch) {
    while (true) {
      long wait;
      synchronized (this) {
        long now = System.currentTimeMillis();
        wait = 0;
        for (int i = 0; i < windows.length; i++) {
          Window w = windows[i];
          while (!w.hits().isEmpty() && now - w.hits().peekFirst() >= w.durationMs()) {
            w.hits().pollFirst();
          }
          int ceiling = batch ? batchCeilings[i] : w.max();
          if (w.hits().size() >= ceiling) {
            long oldest = w.hits().isEmpty() ? now : w.hits().peekFirst();
            wait = Math.max(wait, w.durationMs() - (now - oldest) + 5);
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
   * snapshot for the B4 quota widget. {@code max} is the FULL token cap (the batch reserve is a pacing
   * ceiling, not a smaller budget). Thread-safe (shares the {@link #awaitAndRecord} monitor).
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
