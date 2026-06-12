package in.arthayantra.marketdata.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.autoconfigure.cache.CacheManagerCustomizer;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Named Caffeine caches with per-cache TTLs (B-4 — every cache has a named consumer; no
 * speculative caches): {@code instruments} 1 h, {@code expiries} 10 m, {@code kite-status} 30 s.
 * Stats are recorded so Boot's cache metrics ({@code cache_gets_total{cache,result}}) feed the
 * Phase 45 dashboards.
 */
@Configuration
@org.springframework.cache.annotation.EnableCaching
public class CacheConfig {

  /** Registers the named caches. */
  @Bean
  public CacheManagerCustomizer<CaffeineCacheManager> arthaCaches() {
    return cacheManager -> {
      cacheManager.registerCustomCache(
          "instruments",
          Caffeine.newBuilder()
              .expireAfterWrite(Duration.ofHours(1))
              .maximumSize(20_000)
              .recordStats()
              .build());
      cacheManager.registerCustomCache(
          "expiries",
          Caffeine.newBuilder()
              .expireAfterWrite(Duration.ofMinutes(10))
              .maximumSize(2_000)
              .recordStats()
              .build());
      cacheManager.registerCustomCache(
          "kite-status",
          Caffeine.newBuilder()
              .expireAfterWrite(Duration.ofSeconds(30))
              .maximumSize(16)
              .recordStats()
              .build());
    };
  }
}
