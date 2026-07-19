package in.arthayantra.marketdata.upstox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * Live-profile wiring for the Wave-U3 direct-Upstox v3 WS market-data feed client — kept in its OWN
 * {@code @Configuration} (mirroring {@link UpstoxQuoteConfig}) so the WS feed source is registered
 * additively. The {@link UpstoxMarketFeedClient} binds ONLY when {@code
 * artha.marketdata.source.ticker=upstox}; absent ⇒ the Kite WS ticker stays the bound default (the
 * unchanged fallback). On the long-lived 1-yr analytics token (the same {@link
 * UpstoxAnalyticsProperties} secret-file token as the U1/U2 paths), so the live tick feed survives a
 * missed daily Kite login. The {@code options}-module bridge ({@code UpstoxLiveTickFeedAdapter})
 * consumes this client; the {@code kite.upstoxfeed} feed wiring reaches it only through the {@code
 * kite}-declared {@code UpstoxLiveTickFeed} port (no {@code kite → upstox} edge / no Modulith cycle).
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(UpstoxAnalyticsProperties.class)
public class UpstoxMarketFeedClientConfig {

  /**
   * The hand-rolled Upstox v3 WS feed client — bound only when the ticker is routed to Upstox. The WS
   * authorize-GET debits the ONE shared token budget (EXT-02, defined in {@link UpstoxAnalyticsConfig},
   * present whenever {@code source.ticker=upstox}).
   */
  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.ticker", havingValue = "upstox")
  public UpstoxMarketFeedClient upstoxMarketFeedClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxMarketFeedClient(restClientBuilder, properties, upstoxRateLimiter);
  }
}
