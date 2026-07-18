package in.arthayantra.marketdata.upstox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Token-scoped sliding-window rate limiter for the Upstox analytics token's per-token caps. Upstox
 * enforces 50 req/s, 500 req/min AND <b>2000 req / 30 min</b> — the 30-minute window is the binding
 * constraint for a long historical pull, and ignoring it (a per-call throttle only paces the second)
 * is what exhausted the quota and 429'd. Conservative margins (45 / 450 / 1800), the same three-window
 * model marketcalls/ExpiryTrack uses.
 *
 * <p><b>ONE budget per token (EXT-02).</b> A single instance of this class is a Spring bean shared by
 * <em>every</em> client on the analytics token — the backfill walker, the fundamentals bulk walk, the
 * daily contract canary AND the live capture / quote / margin / ws-authorize / page clients — so the
 * whole token draws from the ONE 2000/30min budget instead of each client fragmenting it with its own
 * instance (or running unmetered).
 *
 * <p><b>Two priorities so a backfill can never starve the live path.</b>
 *
 * <ul>
 *   <li><b>Live path</b> ({@link #tryAcquire(long)}): a BOUNDED wait for a slot at the FULL token cap.
 *       On timeout it returns {@code false} (never records) so the caller fails soft FAST — a live
 *       quote / chain / margin call must never park a thread for up to 30 minutes (the armed F9
 *       governor times out at 2 s and would allow the entry while a market-data thread stayed stuck).
 *   <li><b>Batch path</b> ({@link #acquireForBatch()}): an UNBOUNDED blocking wait for a slot below a
 *       reduced ceiling that reserves {@link #DEFAULT_LIVE_RESERVE_FRACTION} of every window for the
 *       live path, AND it PAUSES entirely while the market is open. A heavy expired-contract backfill
 *       is a background job that can run off-hours; pausing it during the session hands the whole token
 *       to the live capture path (a missed live OI-capture interval is forward-capture-only and
 *       irreplaceable), and the off-hours reserve still keeps headroom for any sporadic live call.
 * </ul>
 *
 * <p>Both paths record into the same windows (one honest per-token count) — only the ceiling each
 * checks, the bound, and the market-hours pause differ.
 */
public final class UpstoxRateLimiter {

  /**
   * Fraction of every window the batch path leaves for the live path OFF-HOURS (during market hours
   * the batch is paused entirely, so live owns the whole budget then). The batch may consume only
   * {@code 1 - fraction} of each window.
   */
  static final double DEFAULT_LIVE_RESERVE_FRACTION = 0.20;

  /**
   * The live path's bounded wait (ms). Shorter than the strategy-signal margin caller's 2 s timeout,
   * so a saturated budget makes the margin client return {@code unpriced} well before that caller
   * gives up — never a parked thread.
   */
  public static final long LIVE_ACQUIRE_TIMEOUT_MS = 1_000;

  /** How often the paused batch path re-checks whether the market has closed. */
  private static final long BATCH_MARKET_PAUSE_POLL_MS = 30_000;

  private record Window(String label, int max, long durationMs, Deque<Long> hits) {}

  /** Per-window quota usage — the read model for the B4 quota widget. */
  public record WindowStat(String window, int used, int max, int remaining) {}

  private final Window[] windows;
  /** Per-window ceiling the batch path may fill to ({@code max - reserve}); the live path uses {@code max}. */
  private final int[] batchCeilings;
  /** True while the market is open — the batch path pauses so live owns the token during the session. */
  private final BooleanSupplier marketOpen;

  /** Production limiter with NO market gate (batch never pauses) — the shape existing tests construct. */
  public UpstoxRateLimiter() {
    this(() -> false);
  }

  /** Production limiter: 45/s · 450/min · 1800/30min windows, 20% reserved off-hours, paused while open. */
  public UpstoxRateLimiter(BooleanSupplier marketOpen) {
    this(
        DEFAULT_LIVE_RESERVE_FRACTION,
        marketOpen,
        new String[] {"1s", "1m", "30m"},
        new long[] {1_000L, 60_000L, 1_800_000L},
        new int[] {45, 450, 1_800});
  }

  /** Test/tuning constructor — explicit windows, reserve fraction, and market gate. */
  UpstoxRateLimiter(
      double liveReserveFraction,
      BooleanSupplier marketOpen,
      String[] labels,
      long[] durationsMs,
      int[] maxes) {
    this.marketOpen = marketOpen;
    this.windows = new Window[labels.length];
    this.batchCeilings = new int[labels.length];
    for (int i = 0; i < labels.length; i++) {
      windows[i] = new Window(labels[i], maxes[i], durationsMs[i], new ArrayDeque<>());
      batchCeilings[i] = (int) Math.floor(maxes[i] * (1.0 - liveReserveFraction));
    }
  }

  /**
   * Live-critical path — waits up to {@code timeoutMillis} for a slot at the FULL token cap in every
   * window. Returns {@code true} and records the hit if acquired, {@code false} on timeout (nothing
   * recorded) so the caller fails soft FAST rather than parking. Thread-safe.
   */
  public boolean tryAcquire(long timeoutMillis) {
    return awaitAndRecord(fullCeilings(), timeoutMillis);
  }

  /** Live-critical path with the default {@link #LIVE_ACQUIRE_TIMEOUT_MS} bound. */
  public boolean tryAcquire() {
    return tryAcquire(LIVE_ACQUIRE_TIMEOUT_MS);
  }

  /**
   * Batch/backfill path — PAUSES while the market is open (so the live path owns the token during the
   * session), then blocks UNBOUNDED until a slot below the live-reserved ceiling frees, and records
   * the hit. A background job; never used on a latency-critical path. Thread-safe.
   */
  public void acquireForBatch() {
    while (marketOpen.getAsBoolean()) {
      sleep(BATCH_MARKET_PAUSE_POLL_MS);
    }
    awaitAndRecord(batchCeilings, Long.MAX_VALUE);
  }

  private int[] fullCeilings() {
    int[] ceilings = new int[windows.length];
    for (int i = 0; i < windows.length; i++) {
      ceilings[i] = windows[i].max();
    }
    return ceilings;
  }

  /** Blocks until a request fits every window at {@code ceilings}, or {@code timeoutMillis} elapses. */
  private boolean awaitAndRecord(int[] ceilings, long timeoutMillis) {
    long deadline =
        timeoutMillis == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;
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
          if (w.hits().size() >= ceilings[i]) {
            long oldest = w.hits().isEmpty() ? now : w.hits().peekFirst();
            wait = Math.max(wait, w.durationMs() - (now - oldest) + 5);
          }
        }
        if (wait <= 0) {
          for (Window w : windows) {
            w.hits().addLast(now);
          }
          return true;
        }
      }
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) {
        return false;
      }
      sleep(Math.min(wait, remaining));
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while rate-limiting", e);
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
