package in.arthayantra.gateway.auth;

import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A.2.6 anti-brute-force: 5 login attempts/min per source IP, then 429 with a 15-minute cooldown
 * counter in Redis. The gateway's Redis {@code RequestRateLimiter} covers routed traffic; login is
 * a gateway-local endpoint, so this small limiter implements the same policy for it.
 */
@Component
public class LoginRateLimiter {

  static final int MAX_ATTEMPTS_PER_MINUTE = 5;
  static final Duration WINDOW = Duration.ofMinutes(1);
  static final Duration COOLDOWN = Duration.ofMinutes(15);

  private final ReactiveStringRedisTemplate redis;

  public LoginRateLimiter(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Registers one attempt from {@code ip} and decides whether it may proceed. The attempt that
   * exceeds the per-minute budget starts the cooldown.
   */
  public Mono<Boolean> tryAcquire(String ip) {
    String cooldownKey = "login:cooldown:" + ip;
    String attemptsKey = "login:attempts:" + ip;
    return redis
        .hasKey(cooldownKey)
        .flatMap(
            coolingDown -> {
              if (Boolean.TRUE.equals(coolingDown)) {
                return Mono.just(false);
              }
              return redis
                  .opsForValue()
                  .increment(attemptsKey)
                  .flatMap(
                      attempts -> {
                        Mono<Boolean> setWindow =
                            attempts == 1L
                                ? redis.expire(attemptsKey, WINDOW)
                                : Mono.just(Boolean.TRUE);
                        if (attempts > MAX_ATTEMPTS_PER_MINUTE) {
                          return setWindow
                              .then(redis.opsForValue().set(cooldownKey, "1", COOLDOWN))
                              .thenReturn(false);
                        }
                        return setWindow.thenReturn(true);
                      });
            });
  }

  /** Remaining cooldown for the 429 envelope's {@code retryAfterMs} detail. */
  public Mono<Long> cooldownRemainingMs(String ip) {
    return redis
        .getExpire("login:cooldown:" + ip)
        .map(ttl -> ttl.isNegative() ? 0L : ttl.toMillis())
        .defaultIfEmpty(0L);
  }
}
