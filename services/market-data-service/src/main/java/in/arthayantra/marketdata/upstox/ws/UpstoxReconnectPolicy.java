package in.arthayantra.marketdata.upstox.ws;

/**
 * Exponential-backoff reconnect policy for the Upstox v3 market-data socket (Wave U3) — a pure,
 * deterministic state machine so the supervisor's reconnect timing is unit-asserted without a real
 * socket or wall-clock sleep. Mirrors the proven OpenAlgo reference: {@code base * 2^(attempt-1)},
 * capped at {@code max}; the attempt counter resets to 0 on every successful (re)connect, so the cap
 * bounds back-to-back failures within ONE disconnection storm, not the process lifetime.
 */
public final class UpstoxReconnectPolicy {

  private final long baseDelayMillis;
  private final long maxDelayMillis;
  private final int maxAttempts;
  private int attempts;

  /** Defaults match the reference: 2 s base, 30 s cap, 50 attempts per storm. */
  public UpstoxReconnectPolicy() {
    this(2_000L, 30_000L, 50);
  }

  public UpstoxReconnectPolicy(long baseDelayMillis, long maxDelayMillis, int maxAttempts) {
    this.baseDelayMillis = baseDelayMillis;
    this.maxDelayMillis = maxDelayMillis;
    this.maxAttempts = maxAttempts;
  }

  /** Records a failed (re)connect attempt and returns the backoff to wait before the next try. */
  public long nextDelayMillis() {
    attempts++;
    long delay = baseDelayMillis * (1L << Math.min(attempts - 1, 30));
    return Math.min(delay, maxDelayMillis);
  }

  /** True once back-to-back failures have exhausted the per-storm budget. */
  public boolean exhausted() {
    return attempts >= maxAttempts;
  }

  /** Resets the attempt counter — called on every successful (re)connect. */
  public void onConnected() {
    attempts = 0;
  }

  /** Current consecutive-failure count (diagnostics/tests). */
  public int attempts() {
    return attempts;
  }
}
