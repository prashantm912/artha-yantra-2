package in.arthayantra.strategysignal.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** System clock, overridable in tests (same pattern as market-data-service). */
@Configuration
public class ClockConfig {

  /** UTC system clock unless a test pins its own. */
  @Bean
  @ConditionalOnMissingBean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
