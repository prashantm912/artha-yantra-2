package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * Live-profile wiring for the Wave-U2 direct-Upstox quote client — kept in its OWN {@code
 * @Configuration} (not {@link UpstoxAnalyticsConfig}) so the quote source is registered additively.
 * The {@link UpstoxQuoteClient} binds ONLY when {@code artha.marketdata.source.quotes=upstox}; absent
 * ⇒ the Kite {@code LiveQuoteGateway} stays the bound {@link in.arthayantra.marketdata.kite.QuoteGateway}
 * default (the unchanged fallback). On the long-lived 1-yr analytics token (the same {@link
 * UpstoxAnalyticsProperties} secret-file token as the U1 option-chain path), so quotes survive a
 * missed daily Kite login. The flag-selected {@code QuoteGateway} adapter ({@code
 * in.arthayantra.marketdata.options.UpstoxQuoteGateway}) consumes this bean.
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(UpstoxAnalyticsProperties.class)
public class UpstoxQuoteConfig {

  /**
   * The hand-rolled Upstox market-quote client — bound only when quotes are routed to Upstox. Draws
   * from the ONE token-scoped {@link UpstoxRateLimiter} bean (EXT-02, defined in {@link
   * UpstoxAnalyticsConfig}, present whenever {@code source.quotes=upstox}), so live quotes share the
   * token's 2000/30min budget with every other analytics-token client instead of running unmetered.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.quotes", havingValue = "upstox")
  public UpstoxQuoteClient upstoxQuoteClient(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      UpstoxRateLimiter upstoxRateLimiter) {
    return new UpstoxQuoteClient(restClientBuilder, properties, upstoxRateLimiter);
  }

  /**
   * The Upstox F&amp;O instrument-master client — bound when EITHER the quote ({@code source.quotes})
   * or the ticker ({@code source.ticker}) path is routed to Upstox, since both seams use it to resolve
   * FUT/option keys (Wave-U4 enabler). Absent ⇒ FUT/option stays unmapped (pre-U4 behavior). Its host
   * is the public assets CDN ({@code assets.upstox.com}) — NO token — so it needs no analytics secret.
   */
  @Bean
  @ConditionalOnExpression(
      "'${artha.marketdata.source.quotes:kite}' == 'upstox' "
          + "or '${artha.marketdata.source.ticker:kite}' == 'upstox' "
          // E1 v2: the FUT-key master lookup also drives the on-demand Upstox Market-Movers radar
          // (UpstoxFnoDailyReader), so it must exist whenever analytics is on, not only on the
          // quote/ticker cutover. Host is the token-free assets CDN — no analytics secret needed.
          + "or '${artha.upstox.analytics.enabled:false}' == 'true'")
  public UpstoxFnoMasterClient upstoxFnoMasterClient(
      RestClient.Builder restClientBuilder, ObjectMapper mapper, UpstoxAnalyticsProperties properties) {
    return new UpstoxFnoMasterClient(restClientBuilder, mapper, properties);
  }
}
