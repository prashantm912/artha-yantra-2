package in.arthayantra.marketdata.kite;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the Kite REST rate-limiter headroom to the shared Redis key {@code kite:rate-budget} so
 * the edge-gateway system-status rollup — which reads only the Redis shared-state keys, never a
 * per-service REST fan-out — can surface {@code kite.rateBudget}. That field stayed {@code null} for
 * want of a producer ({@code SystemStatusController}: "limiter metrics live in market-data").
 *
 * <p>The value is the fraction of the HISTORICAL limiter's per-cycle budget currently available in
 * {@code [0,1]} (1.0 = full headroom / idle, 0.0 = saturated) — the same bucket the
 * {@code ay_kite_rate_limiter_saturation} gauge reads (budget = 1 − saturation). Written every few
 * seconds; the gateway caches its rollup for 5 s, so a ~15 s cadence is ample. Fully fail-soft: a
 * Redis hiccup or an unconfigured limiter must never break anything.
 */
@Component
public class KiteRateBudgetPublisher {

  /** The shared-state Redis key the gateway reads (COMMON §3 {@code kite.*} namespace). */
  public static final String KEY = "kite:rate-budget";

  private static final Logger log = LoggerFactory.getLogger(KiteRateBudgetPublisher.class);

  private final RateLimiterRegistry rateLimiters;
  private final StringRedisTemplate redis;

  /** Wires the resilience4j registry + the shared Redis. */
  public KiteRateBudgetPublisher(RateLimiterRegistry rateLimiters, StringRedisTemplate redis) {
    this.rateLimiters = rateLimiters;
    this.redis = redis;
  }

  /** Snapshots the historical limiter's headroom into Redis on a fixed cadence (fail-soft). */
  @Scheduled(fixedRateString = "${artha.kite.rate-budget.publish-ms:15000}")
  public void publish() {
    try {
      RateLimiter limiter =
          rateLimiters.rateLimiter(KiteCallExecutor.Family.HISTORICAL.limiterName);
      double budget =
          budgetFraction(
              limiter.getMetrics().getAvailablePermissions(),
              limiter.getRateLimiterConfig().getLimitForPeriod());
      redis.opsForValue().set(KEY, Double.toString(budget));
    } catch (RuntimeException e) {
      log.debug("kite rate-budget publish skipped: {}", e.getMessage());
    }
  }

  /**
   * The available fraction of one limiter cycle's budget, clamped to {@code [0,1]}. A {@code period ≤ 0}
   * (no configured budget) reads as fully available (1.0) rather than dividing by zero; a transient
   * negative {@code available} (threads queued past the budget) floors at 0.0. Pure for a unit test.
   */
  static double budgetFraction(int available, int period) {
    if (period <= 0) {
      return 1.0;
    }
    int clamped = Math.max(0, Math.min(available, period));
    return (double) clamped / period;
  }
}
