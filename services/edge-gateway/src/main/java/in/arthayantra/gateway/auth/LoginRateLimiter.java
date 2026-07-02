package in.arthayantra.gateway.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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

  /**
   * Atomic INCR + guaranteed TTL (audit P2). The old INCR-then-conditional-EXPIRE was two commands,
   * so a crash (or a dropped EXPIRE round-trip) between them left the attempts key with NO TTL —
   * and under {@code volatile-lru} a TTL-less key is never evicted, permanently locking the single
   * owner out (every later attempt increments past 5 and re-arms the cooldown; recovery was a
   * manual {@code redis-cli DEL}). This script INCRs and, whenever the key carries no expiry
   * ({@code PTTL < 0} — the just-created key OR a legacy TTL-less one), sets the window in the SAME
   * atomic execution. Returns the new attempt count.
   */
  private static final RedisScript<Long> INCR_WITH_TTL =
      RedisScript.of(
          "local n = redis.call('INCR', KEYS[1])\n"
              + "if redis.call('PTTL', KEYS[1]) < 0 then\n"
              + "  redis.call('PEXPIRE', KEYS[1], ARGV[1])\n"
              + "end\n"
              + "return n",
          Long.class);

  private final ReactiveStringRedisTemplate redis;

  /** Wires the Redis counters. */
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
                  .execute(
                      INCR_WITH_TTL,
                      List.of(attemptsKey),
                      List.of(Long.toString(WINDOW.toMillis())))
                  .next()
                  .flatMap(
                      attempts -> {
                        if (attempts > MAX_ATTEMPTS_PER_MINUTE) {
                          return redis
                              .opsForValue()
                              .set(cooldownKey, "1", COOLDOWN)
                              .thenReturn(false);
                        }
                        return Mono.just(true);
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
