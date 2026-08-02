package in.arthayantra.marketdata.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Phase-11 B-3 resilience stack: pacing, retry policy, saturation and breaker mapping. */
class KiteCallExecutorTest {

  private static KiteCallExecutor executor(RateLimiterConfig limiter, CircuitBreakerConfig breaker) {
    return new KiteCallExecutor(
        RateLimiterRegistry.of(limiter), CircuitBreakerRegistry.of(breaker), new SimpleMeterRegistry());
  }

  private static CircuitBreakerConfig lenientBreaker() {
    return CircuitBreakerConfig.custom().minimumNumberOfCalls(1_000).build();
  }

  @Test
  void fiftyRequestBurstNeverExceedsThreePerSecond() throws Exception {
    // the production kite-historical budget: 3 permits / 1 s, queue rather than reject
    KiteCallExecutor exec =
        executor(
            RateLimiterConfig.custom()
                .limitForPeriod(3)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(60))
                .build(),
            lenientBreaker());

    List<Long> grantNanos = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch done = new CountDownLatch(50);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) {
        pool.execute(
            () -> {
              exec.execute(
                  KiteCallExecutor.Family.HISTORICAL,
                  () -> {
                    grantNanos.add(System.nanoTime());
                    return null;
                  });
              done.countDown();
            });
      }
      assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    }

    List<Long> sorted = grantNanos.stream().sorted().toList();
    assertThat(sorted).hasSize(50);
    // 50 permits at 3/s must take ≥ 16 refresh cycles end to end
    long elapsedMs = (sorted.get(49) - sorted.get(0)) / 1_000_000;
    assertThat(elapsedMs)
        .as("50-burst at 3/s cannot finish faster than ~16 s (took %d ms)", elapsedMs)
        .isGreaterThanOrEqualTo(15_000);
    // any 7 consecutive grants must span more than one full refresh window
    for (int i = 0; i + 6 < sorted.size(); i++) {
      long windowMs = (sorted.get(i + 6) - sorted.get(i)) / 1_000_000;
      assertThat(windowMs)
          .as("grants %d..%d clustered into %d ms — exceeds 3/s", i, i + 6, windowMs)
          .isGreaterThanOrEqualTo(950);
    }
  }

  @Test
  void kiteRetryAfterIsHonoredThenCallSucceeds() {
    KiteCallExecutor exec =
        executor(
            RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build(),
            lenientBreaker());

    AtomicInteger attempts = new AtomicInteger();
    long start = System.nanoTime();
    String result =
        exec.execute(
            KiteCallExecutor.Family.HISTORICAL,
            () -> {
              if (attempts.incrementAndGet() == 1) {
                throw new KiteCallExecutor.KiteRateLimitedException(400);
              }
              return "ok";
            });
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result).isEqualTo("ok");
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(elapsedMs).as("Retry-After 400 ms must be waited out").isGreaterThanOrEqualTo(380);
  }

  @Test
  void clientErrorsAreNeverRetried() {
    KiteCallExecutor exec =
        executor(
            RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build(),
            lenientBreaker());

    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                exec.execute(
                    KiteCallExecutor.Family.HISTORICAL,
                    () -> {
                      attempts.incrementAndGet();
                      throw new HttpClientErrorException(HttpStatus.FORBIDDEN);
                    }))
        .isInstanceOf(HttpClientErrorException.class);
    assertThat(attempts.get()).as("4xx is terminal — exactly one attempt").isEqualTo(1);
  }

  @Test
  void limiterSaturationMapsToLocal429() {
    KiteCallExecutor exec =
        executor(
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(5))
                .timeoutDuration(Duration.ZERO)
                .build(),
            lenientBreaker());

    exec.execute(KiteCallExecutor.Family.HISTORICAL, () -> "first");

    assertThatThrownBy(() -> exec.execute(KiteCallExecutor.Family.HISTORICAL, () -> "second"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(429);
              assertThat(e.code()).isEqualTo(ErrorCodes.RATE_LIMIT_LOCAL);
            });
  }

  @Test
  void breakerOpensOnFailureStormAndMapsTo503() {
    KiteCallExecutor exec =
        executor(
            RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build(),
            CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .build());

    // two non-retryable failures trip the 2-call window
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () ->
                  exec.execute(
                      KiteCallExecutor.Family.HISTORICAL,
                      () -> {
                        throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED);
                      }))
          .isInstanceOf(HttpClientErrorException.class);
    }

    assertThatThrownBy(() -> exec.execute(KiteCallExecutor.Family.HISTORICAL, () -> "blocked"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(503);
              assertThat(e.code()).isEqualTo(ErrorCodes.KITE_CIRCUIT_OPEN);
            });
  }

  /**
   * An OPEN breaker must not spend a rate-limiter permit — the decoration order puts the breaker
   * OUTSIDE the limiter precisely so a locally-rejected call never reaches the bucket.
   *
   * <p>This is the F-SYNC defect in miniature (ledger G4). {@code kite-dump} gets ONE permit per 30
   * minutes; with the old order (limiter outside breaker) a single rejected attempt burned the whole
   * window without a network call, and the daily ~08:36 IST {@code INSTRUMENT_SYNC} then failed with
   * {@code local broker DUMP budget saturated}. Sized here with a 1-permit bucket so the assertion is
   * exact rather than statistical.
   */
  @Test
  void openBreakerDoesNotConsumeARateLimiterPermit() {
    // Roomy default bucket so tripping the breaker (via HISTORICAL) is never limiter-bound...
    RateLimiterRegistry limiters =
        RateLimiterRegistry.of(
            RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build());
    // ...and the PRODUCTION kite-dump shape for the bucket under test: 1 permit / 30 min. Registered
    // by name up front; the executor's own lookup returns this same instance.
    RateLimiter dump =
        limiters.rateLimiter(
            "kite-dump",
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(30))
                .timeoutDuration(Duration.ZERO)
                .build());
    KiteCallExecutor exec =
        new KiteCallExecutor(
            limiters,
            CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50)
                    .build()),
            new SimpleMeterRegistry());

    // Trip the shared kite-rest breaker WITHOUT touching the DUMP bucket.
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(
              () ->
                  exec.execute(
                      KiteCallExecutor.Family.HISTORICAL,
                      () -> {
                        throw new HttpClientErrorException(HttpStatus.UNAUTHORIZED);
                      }))
          .isInstanceOf(HttpClientErrorException.class);
    }
    assertThat(dump.getMetrics().getAvailablePermissions()).isEqualTo(1);

    // A DUMP call the now-OPEN breaker rejects...
    assertThatThrownBy(() -> exec.execute(KiteCallExecutor.Family.DUMP, () -> "blocked"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.KITE_CIRCUIT_OPEN));

    // ...must leave the 30-minute budget intact. With the old order this dropped to 0, and the next
    // real INSTRUMENT_SYNC failed with RATE_LIMIT_LOCAL without ever reaching Kite.
    assertThat(dump.getMetrics().getAvailablePermissions()).isEqualTo(1);
  }

  /**
   * Repeated LOCAL rate-limiter rejections must never open the shared breaker.
   *
   * <p>The regression this pins was introduced by the fix above: putting the breaker OUTSIDE the
   * limiter means {@code RequestNotPermitted} propagates through it, and the breaker would score a
   * purely local pacing rejection as an upstream failure. At the production window (10 calls, 50%)
   * five saturations would open the SHARED {@code kite-rest} breaker while Kite is perfectly
   * healthy, 503-ing every family for 30s. {@code application.yml} lists it under
   * {@code ignore-exceptions}; this proves the config is actually load-bearing.
   */
  @Test
  void localLimiterSaturationNeverOpensTheSharedBreaker() {
    RateLimiterRegistry limiters =
        RateLimiterRegistry.of(
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(30))
                .timeoutDuration(Duration.ZERO)
                .build());
    KiteCallExecutor exec =
        new KiteCallExecutor(
            limiters,
            CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(10)
                    .failureRateThreshold(50)
                    // mirrors the production ignore-exceptions entry
                    .ignoreExceptions(RequestNotPermitted.class)
                    .build()),
            new SimpleMeterRegistry());

    exec.execute(KiteCallExecutor.Family.HISTORICAL, () -> "burns the single permit");

    // 12 further calls all fail LOCALLY on the exhausted bucket — well past the 10-call window.
    for (int i = 0; i < 12; i++) {
      assertThatThrownBy(() -> exec.execute(KiteCallExecutor.Family.HISTORICAL, () -> "saturated"))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.RATE_LIMIT_LOCAL));
    }

    assertThat(exec.kiteRestBreaker().getState())
        .as("local pacing must never be scored as an upstream fault")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  /**
   * The DISTINCT, still-live sub-defect (task_4fd8eadc): {@code kite-dump}'s budget is 1 permit /
   * 30 minutes for the WHOLE three-exchange sweep, so an internal {@code Retry} on this family is
   * self-defeating — the one attempt that actually reaches Kite fails, the shared retry
   * immediately tries again, attempt 2 needs a SECOND permit the bucket cannot refill for ~30
   * minutes, and that attempt throws {@code RequestNotPermitted} (non-retryable, so Retry gives
   * up). {@code execute()} then maps it to the exact {@code local broker DUMP budget saturated}
   * 429 text the ORIGINAL F-SYNC symptom used — masking the real, retryable cause behind a
   * saturation message that never happened. DUMP must surface its one real failure untouched;
   * {@code InstrumentSyncScheduler.morningSyncCatchUp} is the actual retry mechanism for this
   * family, not an internal Retry.
   */
  @Test
  void dumpFamilyNeverRetriesInternally() {
    KiteCallExecutor exec =
        executor(
            RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(30))
                .timeoutDuration(Duration.ZERO)
                .build(),
            lenientBreaker());

    AtomicInteger invocations = new AtomicInteger();
    assertThatThrownBy(
            () ->
                exec.execute(
                    KiteCallExecutor.Family.DUMP,
                    () -> {
                      invocations.incrementAndGet();
                      throw new ResourceAccessException("kite dump: connection timed out");
                    }))
        .isInstanceOf(ResourceAccessException.class);
    assertThat(invocations.get())
        .as(
            "DUMP must never retry internally — a 2nd attempt would need a permit the 30-min"
                + " bucket cannot refill")
        .isEqualTo(1);
  }
}
