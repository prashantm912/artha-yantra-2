package in.arthayantra.marketdata.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Injectable clock — schedulers and accrual steps stay unit-testable (B-12). */
@Configuration
public class ClockConfig {

  /** System clock; tests override with fixed/mutable clocks. */
  @Bean
  @ConditionalOnMissingBean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
