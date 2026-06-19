package in.arthayantra.marketdata.openalgo.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.openalgo.canary.OpenAlgoContractCanary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

/**
 * Live-profile OpenAlgo wiring (plan §3, the broker-swap spine). Each gateway binds the SAME domain
 * port the Kite live adapter binds, selected per capability by {@code artha.marketdata.source.*}
 * (§17.2). The DEFAULT is {@code kite} (so Phase 0 changes no behaviour — these beans are absent
 * unless a capability is explicitly flipped to {@code openalgo}; the inverse-guarded Kite beans in
 * {@code LiveKiteConfig} bind by default). The actual production cutover + OI-coverage canary is
 * Phase 1 (§4); Phase 0 only stands the wiring up behind the ports.
 *
 * <p>AGPL boundary: the OpenAlgo appliance is consumed only over its {@code /api/v1/} REST surface;
 * its source is never merged here.
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(OpenAlgoProperties.class)
public class OpenAlgoConfig {

  /** OpenAlgo spot quotes when {@code artha.marketdata.source.quotes=openalgo}. */
  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.quotes", havingValue = "openalgo")
  public QuoteGateway openAlgoQuoteGateway(
      RestClient.Builder restClientBuilder,
      OpenAlgoProperties properties,
      KiteCallExecutor executor,
      ObjectMapper objectMapper) {
    return new OpenAlgoQuoteGateway(
        restClientBuilder, properties.baseUrl(), properties.resolveApiKey(), executor, objectMapper);
  }

  /** OpenAlgo historical OHLCV when {@code artha.marketdata.source.candles=openalgo}. */
  @Bean
  @ConditionalOnProperty(name = "artha.marketdata.source.candles", havingValue = "openalgo")
  public HistoricalCandleGateway openAlgoHistoricalCandleGateway(
      RestClient.Builder restClientBuilder,
      OpenAlgoProperties properties,
      KiteCallExecutor executor,
      ObjectMapper objectMapper) {
    return new OpenAlgoHistoricalCandleGateway(
        restClientBuilder, properties.baseUrl(), properties.resolveApiKey(), executor, objectMapper);
  }

  /**
   * Daily OpenAlgo contract canary — wired only when {@code artha.openalgo.contract-canary-enabled
   * =true} (Phase 1 entry-gate, §17.11: must be green against the LIVE appliance before any
   * {@code source.*} flips to openalgo). Off by default so a Kite-only live stack needs no OpenAlgo
   * API key.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.openalgo.contract-canary-enabled", havingValue = "true")
  public OpenAlgoContractCanary openAlgoContractCanary(
      RestClient.Builder restClientBuilder,
      OpenAlgoProperties properties,
      KiteCallExecutor executor,
      StringRedisTemplate redis,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    return new OpenAlgoContractCanary(
        restClientBuilder,
        properties.baseUrl(),
        properties.resolveApiKey(),
        executor,
        redis,
        ntfy,
        calendar,
        clock,
        objectMapper,
        meterRegistry);
  }
}
