package in.arthayantra.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The atomic-TTL invariant (audit P2) against a real Redis: after ANY {@code tryAcquire}, the
 * attempts key always carries a positive TTL — the pre-#P2 non-atomic INCR/EXPIRE could leave it
 * TTL-less (a crash between the two commands), which under volatile-lru never evicts and
 * permanently locks the owner out. Also heals a legacy TTL-less key on the next attempt.
 */
@SpringBootTest
@Testcontainers
class LoginRateLimiterIntegrationTest {

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @Autowired private LoginRateLimiter limiter;
  @Autowired private ReactiveStringRedisTemplate redis;

  private static final String IP = "10.0.0.7";
  private static final String KEY = "login:attempts:" + IP;

  @BeforeEach
  void clean() {
    redis.keys("login:*").flatMap(redis::delete).blockLast();
  }

  @Test
  void everyAttemptLeavesAPositiveTtlOnTheCounter() {
    assertThat(limiter.tryAcquire(IP).block()).isTrue();
    Duration ttl = redis.getExpire(KEY).block();
    assertThat(ttl).isNotNull().isPositive();
    assertThat(ttl).isLessThanOrEqualTo(LoginRateLimiter.WINDOW);
  }

  @Test
  void aLegacyTtllessCounterIsHealedOnTheNextAttempt() {
    // Simulate the crash-orphaned key: a value with NO expiry (reactive getExpire → ZERO).
    redis.opsForValue().set(KEY, "3").block();
    assertThat(redis.getExpire(KEY).block()).isEqualTo(Duration.ZERO); // no expiry set

    limiter.tryAcquire(IP).block();

    assertThat(redis.getExpire(KEY).block()).isPositive(); // window restored — no permanent lockout
  }

  @Test
  void theSixthAttemptInTheWindowIsDeniedAndArmsTheCooldown() {
    for (int i = 0; i < LoginRateLimiter.MAX_ATTEMPTS_PER_MINUTE; i++) {
      assertThat(limiter.tryAcquire(IP).block()).isTrue();
    }
    assertThat(limiter.tryAcquire(IP).block()).isFalse();
    assertThat(redis.getExpire("login:cooldown:" + IP).block()).isPositive();
    // and further attempts stay denied while the cooldown holds
    assertThat(limiter.tryAcquire(IP).block()).isFalse();
  }
}
