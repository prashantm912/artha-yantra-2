package in.arthayantra.marketdata.kite;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * The B-3 resilience stack composed once: {@code Retry( CircuitBreaker( RateLimiter( call ) ) )}.
 * Every attempt that the breaker admits re-acquires a limiter permit (strict pacing — retries never
 * burst past the budget) and is recorded by the breaker; a call the breaker REJECTS never reaches
 * the bucket, so an outage cannot silently drain a scarce budget (see {@link #execute}). Retry: idempotent GETs only, max 4 attempts,
 * exponential 500 ms ×2 capped 8 s with FULL JITTER, honoring Kite's {@code Retry-After} on 429;
 * never on 4xx auth/validation. Limiter saturation (after the 5 s queue) maps to
 * {@code 429 RATE_LIMIT_LOCAL} — surfaced distinctly from Kite's own 429.
 */
@Component
public class KiteCallExecutor {

  /**
   * Broker-REST endpoint families with their B-3 limiter + breaker instances. Each broker gets its
   * own breaker so an OpenAlgo outage never trips Kite-direct calls (plan §3/§4 per-capability
   * source flag keeps both paths alive).
   */
  public enum Family {
    HISTORICAL("kite-historical", "kite-rest"),
    QUOTE("kite-quote", "kite-rest"),
    DUMP("kite-dump", "kite-rest"),
    MISC("kite-misc", "kite-rest"),
    OPENALGO_QUOTE("openalgo-quote", "openalgo-rest"),
    OPENALGO_HISTORICAL("openalgo-historical", "openalgo-rest");

    final String limiterName;
    final String breakerName;

    Family(String limiterName, String breakerName) {
      this.limiterName = limiterName;
      this.breakerName = breakerName;
    }
  }

  private final RateLimiterRegistry rateLimiters;
  private final CircuitBreakerRegistry circuitBreakers;
  private final Retry retry;

  /** Wires the registries (yml-configured instances) + the programmatic retry policy. */
  public KiteCallExecutor(
      RateLimiterRegistry rateLimiters,
      CircuitBreakerRegistry circuitBreakers,
      MeterRegistry meterRegistry) {
    this.rateLimiters = rateLimiters;
    this.circuitBreakers = circuitBreakers;
    this.retry =
        Retry.of(
            "kite-rest",
            RetryConfig.custom()
                .maxAttempts(4)
                .intervalBiFunction(
                    (attempt, either) -> {
                      // honor Kite's Retry-After on 429 (B-3)
                      if (either.isLeft() && either.getLeft() instanceof KiteRateLimitedException kre
                          && kre.retryAfterMs() > 0) {
                        return kre.retryAfterMs();
                      }
                      long backoff =
                          IntervalFunction.ofExponentialRandomBackoff(
                                  Duration.ofMillis(500), 2.0, 1.0)
                              .apply(attempt);
                      return Math.min(backoff, 8_000);
                    })
                .retryOnException(KiteCallExecutor::isRetryable)
                .build());
    // ay_kite_rate_limiter_saturation: 1 - available/period on the historical bucket
    RateLimiter historical = rateLimiters.rateLimiter(Family.HISTORICAL.limiterName);
    meterRegistry.gauge(
        "ay_kite_rate_limiter_saturation",
        historical,
        limiter -> {
          int period = limiter.getRateLimiterConfig().getLimitForPeriod();
          int available = limiter.getMetrics().getAvailablePermissions();
          return period == 0 ? 0 : Math.max(0, (period - available) / (double) period);
        });
  }

  /** Kite's own 429 with its Retry-After hint. */
  public static class KiteRateLimitedException extends RuntimeException {
    private final long retryAfterMs;

    /** Wraps a Kite 429. */
    public KiteRateLimitedException(long retryAfterMs) {
      super("Kite rate limited (retry-after " + retryAfterMs + " ms)");
      this.retryAfterMs = retryAfterMs;
    }

    /** Retry-After in ms; 0 when absent. */
    public long retryAfterMs() {
      return retryAfterMs;
    }
  }

  static boolean isRetryable(Throwable t) {
    if (t instanceof KiteRateLimitedException) {
      return true;
    }
    if (t instanceof ResourceAccessException || t instanceof IOException) {
      return true;
    }
    if (t instanceof HttpServerErrorException) {
      return true; // 5xx
    }
    if (t instanceof HttpClientErrorException) {
      return false; // 4xx auth/validation — never retried
    }
    return false;
  }

  /**
   * Executes a Kite call under the family's limiter, the kite-rest breaker and retry.
   *
   * <p><b>Decoration order is load-bearing: the BREAKER is outside the LIMITER.</b> It used to be
   * the other way round, which meant the permit was acquired BEFORE the breaker was consulted — so
   * a call rejected by an open breaker still spent its permit without ever reaching the network.
   * Harmless for the 1-per-second families, fatal for {@code kite-dump}, whose budget is **1 permit
   * per 30 minutes**: one rejected attempt burned the whole window, and the retries that followed
   * failed with {@code local broker DUMP budget saturated} rather than the breaker's own message.
   * That is the daily ~08:36 IST {@code INSTRUMENT_SYNC} failure (ledger G4 / F-SYNC).
   *
   * <p>This order is also what resilience4j itself recommends (Retry → CircuitBreaker →
   * RateLimiter → … ). With the breaker CLOSED the behaviour is identical; with it OPEN the permit
   * survives for the retry once the breaker half-opens.
   */
  public <T> T execute(Family family, Supplier<T> call) {
    RateLimiter limiter = rateLimiters.rateLimiter(family.limiterName);
    CircuitBreaker breaker = circuitBreakers.circuitBreaker(family.breakerName);
    Supplier<T> attempt =
        () -> CircuitBreaker.decorateSupplier(breaker, RateLimiter.decorateSupplier(limiter, call)).get();
    try {
      return Retry.decorateSupplier(retry, attempt).get();
    } catch (RequestNotPermitted saturation) {
      throw new ApiException(
          429, ErrorCodes.RATE_LIMIT_LOCAL, "local broker " + family + " budget saturated");
    } catch (CallNotPermittedException open) {
      throw new ApiException(
          503, ErrorCodes.KITE_CIRCUIT_OPEN, family.breakerName + " circuit open; serving cached data");
    }
  }

  /** The kite-rest breaker (status surfaces + tests). */
  public CircuitBreaker kiteRestBreaker() {
    return circuitBreakers.circuitBreaker("kite-rest");
  }
}
