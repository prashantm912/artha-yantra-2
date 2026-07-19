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
 * <em>every</em> client on the analytics token — the backfill walkers, the fundamentals bulk walk, the
 * daily contract canary AND the live capture / quote / margin / ws-authorize / page clients — so the
 * whole token draws from the ONE 2000/30min budget instead of each client fragmenting it (or running
 * unmetered).
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
 *       live path, AND it PAUSES while the caller-supplied {@code batchPaused} gate is true (the
 *       config wires that to the <b>batch-quiet guard window</b>: from one full 30-min rate-window
 *       BEFORE the session open through the close). Pausing 30 min before open guarantees the 30-min
 *       deque has drained of batch hits by the open, so the live capture path owns the WHOLE budget
 *       during the session — a missed live OI-capture interval is forward-capture-only and
 *       irreplaceable. The gate is re-checked at RECORD time: a batch call admitted before the guard
 *       window opened is NOT allowed to record inside it (it re-pauses instead).
 * </ul>
 *
 * <p>Both paths record into the same windows (one honest per-token count) — only the ceiling each
 * checks, the bound, and the guard-window pause differ.
 */
public final class UpstoxRateLimiter {

  /**
   * Fraction of every window the batch path leaves for the live path OFF-HOURS (during the batch-quiet
   * guard window the batch is paused entirely, so live owns the whole budget then). The batch may
   * consume only {@code 1 - fraction} of each window.
   */
  static final double DEFAULT_LIVE_RESERVE_FRACTION = 0.20;

  /**
   * The live path's bounded wait (ms). Shorter than the strategy-signal margin caller's 2 s timeout,
   * so a saturated budget makes the margin client return {@code unpriced} well before that caller
   * gives up — never a parked thread.
   */
  public static final long LIVE_ACQUIRE_TIMEOUT_MS = 1_000;

  /** How often a paused/parked batch call re-checks the guard gate. */
  private static final long BATCH_PAUSE_POLL_MS = 30_000;

  private record Window(String label, int max, long durationMs, Deque<Long> hits) {}

  /** Per-window quota usage — the read model for the B4 quota widget. */
  public record WindowStat(String window, int used, int max, int remaining) {}

  private final Window[] windows;
  /** Per-window ceiling the batch path may fill to ({@code max - reserve}); the live path uses {@code max}. */
  private final int[] batchCeilings;
  /** True while batch work must pause (the batch-quiet guard window); the live path ignores it. */
  private final BooleanSupplier batchPaused;

  /** Production limiter with NO guard window (batch never pauses) — the shape existing tests construct. */
  public UpstoxRateLimiter() {
    this(() -> false);
  }

  /** Production limiter: 45/s · 450/min · 1800/30min windows, 20% reserved off-hours, paused in the guard window. */
  public UpstoxRateLimiter(BooleanSupplier batchPaused) {
    this(
        DEFAULT_LIVE_RESERVE_FRACTION,
        batchPaused,
        new String[] {"1s", "1m", "30m"},
        new long[] {1_000L, 60_000L, 1_800_000L},
        new int[] {45, 450, 1_800});
  }

  /** Test/tuning constructor — explicit windows, reserve fraction, and batch-pause gate. */
  UpstoxRateLimiter(
      double liveReserveFraction,
      BooleanSupplier batchPaused,
      String[] labels,
      long[] durationsMs,
      int[] maxes) {
    this.batchPaused = batchPaused;
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
    long deadline =
        timeoutMillis == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMillis;
    int[] ceilings = fullCeilings();
    while (true) {
      long wait;
      synchronized (this) {
        wait = waitFor(ceilings);
        if (wait <= 0) {
          record();
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

  /** Live-critical path with the default {@link #LIVE_ACQUIRE_TIMEOUT_MS} bound. */
  public boolean tryAcquire() {
    return tryAcquire(LIVE_ACQUIRE_TIMEOUT_MS);
  }

  /**
   * Batch/backfill path — PAUSES while the batch-quiet guard window is open (so the live path owns the
   * token through the session), then blocks UNBOUNDED until a slot below the live-reserved ceiling
   * frees, and records the hit. If the guard window OPENS while it is waiting for a slot, it does NOT
   * record — it re-pauses instead (a call admitted before the window must not record inside it). A
   * background job; never used on a latency-critical path. Thread-safe.
   */
  public void acquireForBatch() {
    while (true) {
      while (batchPaused.getAsBoolean()) {
        sleep(BATCH_PAUSE_POLL_MS);
      }
      if (recordBatchWhileActive()) {
        return;
      }
    }
  }

  /**
   * Waits for a batch-ceiling slot and records it, re-checking the guard gate at record time. Returns
   * {@code false} (nothing recorded) if the guard window opens before a slot is taken, so the caller
   * loops back into the pause.
   */
  private boolean recordBatchWhileActive() {
    while (true) {
      long wait;
      synchronized (this) {
        if (batchPaused.getAsBoolean()) {
          return false; // guard window opened — do not record a batch hit inside it; re-pause
        }
        wait = waitFor(batchCeilings);
        if (wait <= 0) {
          record();
          return true;
        }
      }
      sleep(Math.min(wait, BATCH_PAUSE_POLL_MS)); // poll-bounded so the gate is re-checked promptly
    }
  }

  private int[] fullCeilings() {
    int[] ceilings = new int[windows.length];
    for (int i = 0; i < windows.length; i++) {
      ceilings[i] = windows[i].max();
    }
    return ceilings;
  }

  /** Under the monitor: prune expired hits, then return the ms to wait for a slot under {@code ceilings} (0 = free). */
  private long waitFor(int[] ceilings) {
    long now = System.currentTimeMillis();
    long wait = 0;
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
    return wait;
  }

  /** Under the monitor: record a hit in every window. */
  private void record() {
    long now = System.currentTimeMillis();
    for (Window w : windows) {
      w.hits().addLast(now);
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
   * ceiling, not a smaller budget). Thread-safe (shares the {@link #record} monitor).
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
