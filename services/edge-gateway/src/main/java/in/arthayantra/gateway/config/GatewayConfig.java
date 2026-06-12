package in.arthayantra.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/** Gateway plumbing beans. */
@Configuration
public class GatewayConfig {

  /**
   * Single-user system: the A.2.6 50 req/s valve is a global safety net against runaway frontend
   * loops (a real v1 bug class), not per-user capacity management — one bucket for everything.
   */
  @Bean
  public KeyResolver globalKeyResolver() {
    return exchange -> Mono.just("global");
  }
}
