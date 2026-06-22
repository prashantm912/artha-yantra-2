package in.arthayantra.marketdata.upstox;

import in.arthayantra.marketdata.nse.FiiDiiFetcher;
import in.arthayantra.marketdata.nse.LiveFiiDiiFetcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

  /**
   * Upstox-primary FII/DII cash source (U2), bound as {@code @Primary} when {@code
   * artha.marketdata.source.fiidii=upstox} so the NSE scheduler persists Upstox flows; the NSE
   * {@code LiveFiiDiiFetcher} stays the swap-out fallback. REQUIRES {@code
   * artha.upstox.analytics.enabled=true} (it consumes {@link UpstoxAnalyticsClient}) — flip only
   * after the U1 entitlement probe is green.
   */
  @Bean
  @Primary
  @ConditionalOnProperty(name = "artha.marketdata.source.fiidii", havingValue = "upstox")
  public FiiDiiFetcher upstoxFiiDiiFetcher(
      UpstoxAnalyticsClient client, LiveFiiDiiFetcher nseFallback) {
    return new UpstoxFiiDiiFetcher(client, nseFallback);
  }
}
