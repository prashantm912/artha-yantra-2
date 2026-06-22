package in.arthayantra.marketdata.upstox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * Live-profile Upstox Market-Information wiring (ADR-0002). The analytics client binds only when
 * {@code artha.upstox.analytics.enabled=true} — dormant by default (same default-off discipline as
 * the OpenAlgo data-foundation flags), so a Kite/OpenAlgo-only live stack needs no Upstox token. The
 * dedicated analytics token is read from its secret file inside {@link UpstoxAnalyticsProperties}.
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(UpstoxAnalyticsProperties.class)
public class UpstoxAnalyticsConfig {

  /** The hand-rolled Upstox analytics REST client (entitlement probe in U1; feeds U2+). */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxAnalyticsClient upstoxAnalyticsClient(
      RestClient.Builder restClientBuilder, UpstoxAnalyticsProperties properties) {
    return new UpstoxAnalyticsClient(restClientBuilder, properties);
  }
}
