package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.nse.FiiDerivativeFetcher;
import in.arthayantra.marketdata.nse.FiiDiiFetcher;
import in.arthayantra.marketdata.nse.LiveFiiDiiFetcher;
import in.arthayantra.marketdata.upstox.canary.UpstoxContractCanary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
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
   * The Upstox <b>expired-instruments</b> REST client — feeds {@code ExpiredBackfillService} (backtest
   * data). Same analytics token + base URL as the Market-Information client; bound only when the
   * analytics token is enabled (else the admin {@code /expired-backfill} trigger 503s as unconfigured).
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public UpstoxExpiredInstrumentsClient upstoxExpiredInstrumentsClient(
      RestClient.Builder restClientBuilder, UpstoxAnalyticsProperties properties) {
    return new UpstoxExpiredInstrumentsClient(restClientBuilder, properties);
  }

  /**
   * Direct-Upstox LIVE option-chain client (Wave U1) — the flag-selected {@link
   * in.arthayantra.marketdata.options.OptionChainQuoteSource} for the live OI snapshot. Bound ONLY
   * when {@code artha.marketdata.source.optionchain=upstox}; absent ⇒ {@code OptionsChainService}
   * keeps sourcing per-strike LTP+OI from the Kite {@code QuoteGateway} (the unchanged default). On
   * the long-lived 1-yr analytics token, so OI capture survives a missed daily Kite login.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.optionchain", havingValue = "upstox")
  public UpstoxOptionChainClient upstoxOptionChainClient(
      RestClient.Builder restClientBuilder, UpstoxAnalyticsProperties properties) {
    return new UpstoxOptionChainClient(restClientBuilder, properties);
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

  /**
   * Upstox FII-derivative source (U6) — bound whenever the analytics token is enabled (there is no
   * NSE equivalent, so no source flag and no fallback). The {@code NseEodScheduler} consumes it via
   * an {@code ObjectProvider}, so its absence (analytics off) is a no-op.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.analytics.enabled", havingValue = "true")
  public FiiDerivativeFetcher upstoxFiiDerivativeFetcher(UpstoxAnalyticsClient client) {
    return new UpstoxFiiDerivativeFetcher(client);
  }

  /**
   * Daily Upstox Market-Information contract canary (U7) — wired only when {@code
   * artha.upstox.canary-enabled=true}. Off by default so a stack without the analytics token needs
   * no Upstox probe. Self-scheduled (daily cron, IST); raw-JSON probes diffed vs the committed
   * manifest, ntfy on drift. Separate flag from {@code analytics.enabled} so the canary can be left
   * off (no ntfy noise) even while the analytics consumers run.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.upstox.canary-enabled", havingValue = "true")
  public UpstoxContractCanary upstoxContractCanary(
      RestClient.Builder restClientBuilder,
      UpstoxAnalyticsProperties properties,
      StringRedisTemplate redis,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    return new UpstoxContractCanary(
        restClientBuilder, properties, redis, ntfy, calendar, clock, objectMapper, meterRegistry);
  }
}
