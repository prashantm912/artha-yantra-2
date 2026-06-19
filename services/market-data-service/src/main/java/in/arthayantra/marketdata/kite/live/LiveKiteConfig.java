package in.arthayantra.marketdata.kite.live;

import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.SessionGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Live-profile wiring: the context FAILS FAST at startup when Kite secrets are absent
 * (COMMON §10.3 — never a half-configured live stack). All five B-6 ports now bind their real
 * wire adapters (Phases 9–15) — no stubs remain.
 */
@Configuration
@Profile("live")
@org.springframework.boot.context.properties.EnableConfigurationProperties(KiteHttpProperties.class)
public class LiveKiteConfig {

  /**
   * D13: live without credentials is a startup error, not a degraded runtime. Implemented as a
   * static {@link BeanFactoryPostProcessor} so it fires BEFORE any bean instantiation —
   * deterministic ordering ahead of datasource/repository startup work. Phase 12 adds the
   * AES-GCM master key to the required set.
   */
  @Bean
  public static BeanFactoryPostProcessor liveCredentialsFailFast(Environment environment) {
    return beanFactory -> {
      Path apiKeyFile =
          Path.of(environment.getProperty("artha.kite.api-key-file", "/run/secrets/kite_api_key"));
      Path apiSecretFile =
          Path.of(
              environment.getProperty(
                  "artha.kite.api-secret-file", "/run/secrets/kite_api_secret"));
      Path masterKeyFile =
          Path.of(
              environment.getProperty(
                  "artha.kite.master-key-file", "/run/secrets/artha_master_key"));
      if (!isNonBlankFile(apiKeyFile)
          || !isNonBlankFile(apiSecretFile)
          || !isNonBlankFile(masterKeyFile)) {
        throw new IllegalStateException(
            "live profile requires Kite credentials + master key as Docker secret files ("
                + apiKeyFile
                + ", "
                + apiSecretFile
                + ", "
                + masterKeyFile
                + ") — see deploy/secrets/README.md (D13); mock mode needs none");
      }
    };
  }

  private static boolean isNonBlankFile(Path file) {
    try {
      return Files.isReadable(file) && !Files.readString(file).isBlank();
    } catch (IOException e) {
      return false;
    }
  }

  /** Live session port — backed by the Phase-12 token store. */
  @Bean
  public SessionGateway liveSessionGateway(
      in.arthayantra.marketdata.kite.session.KiteSessionStore store) {
    return new SessionGateway() {
      @Override
      public boolean sessionActive() {
        return store.currentToken().isPresent()
            && store.state() == in.arthayantra.marketdata.kite.session.KiteSessionStore.State.CONNECTED;
      }

      @Override
      public String statusLabel() {
        return "LIVE";
      }
    };
  }

  /** AES-256-GCM under the ARTHA_MASTER_KEY secret (Phase 12). */
  @Bean
  public in.arthayantra.marketdata.kite.session.AesGcmTokenCipher tokenCipher(
      KiteHttpProperties properties) {
    return new in.arthayantra.marketdata.kite.session.AesGcmTokenCipher(
        properties.resolveMasterKey());
  }

  /** The token store — decrypt-and-resume on startup (D13: restarts need no re-login). */
  @Bean
  public in.arthayantra.marketdata.kite.session.KiteSessionStore kiteSessionStore(
      in.arthayantra.marketdata.kite.session.KiteSessionRepository repository,
      in.arthayantra.marketdata.kite.session.AesGcmTokenCipher cipher) {
    var store = new in.arthayantra.marketdata.kite.session.KiteSessionStore(repository, cipher);
    store.loadFromDatabase();
    return store;
  }

  /** Session wire calls (exchange + probe) over the WireMock-able base URL. */
  @Bean
  public in.arthayantra.marketdata.kite.session.SessionWireClient sessionWireClient(
      org.springframework.web.client.RestClient.Builder restClientBuilder,
      KiteHttpProperties properties,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    return new LiveSessionWireClient(restClientBuilder, properties, objectMapper);
  }

  /** Status key writer + change publisher. */
  @Bean
  public in.arthayantra.marketdata.kite.session.SessionStatusPublisher sessionStatusPublisher(
      org.springframework.data.redis.core.StringRedisTemplate redis) {
    return new in.arthayantra.marketdata.kite.session.SessionStatusPublisher(redis);
  }

  /** The OAuth ritual surface. */
  @Bean
  public in.arthayantra.marketdata.kite.session.KiteSessionService liveKiteSessionService(
      in.arthayantra.marketdata.kite.session.SessionWireClient wireClient,
      in.arthayantra.marketdata.kite.session.KiteSessionStore store,
      in.arthayantra.marketdata.kite.session.SessionStatusPublisher statusPublisher,
      in.arthayantra.marketdata.kite.KiteCallExecutor executor,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.canary.ContractCanary canary,
      org.springframework.beans.factory.ObjectProvider<MarketFeed> marketFeed,
      org.springframework.beans.factory.ObjectProvider<in.arthayantra.marketdata.kite.FeedRearm>
          feedRearm) {
    return new in.arthayantra.marketdata.kite.session.LiveKiteSessionService(
        wireClient, store, statusPublisher, executor,
        properties.loginUrlBase(), properties.resolveApiKey(), canary,
        () -> {
          MarketFeed feed = marketFeed.getIfAvailable();
          return feed != null && feed.running();
        },
        () -> {
          in.arthayantra.marketdata.kite.FeedRearm pipeline = feedRearm.getIfAvailable();
          if (pipeline != null) {
            pipeline.restartFeed();
          }
        });
  }

  /** The 5-min health probe — also the canary's first-LIVE-of-the-day trigger (B-9). */
  @Bean
  public in.arthayantra.marketdata.kite.session.SessionHealthProbe sessionHealthProbe(
      in.arthayantra.marketdata.kite.session.SessionWireClient wireClient,
      in.arthayantra.marketdata.kite.session.KiteSessionStore store,
      in.arthayantra.marketdata.kite.session.SessionStatusPublisher statusPublisher,
      io.micrometer.core.instrument.MeterRegistry meterRegistry,
      in.arthayantra.marketdata.kite.canary.ContractCanary canary) {
    var probe =
        new in.arthayantra.marketdata.kite.session.SessionHealthProbe(
            wireClient, store, statusPublisher, meterRegistry);
    probe.setOnLiveHook(canary::maybeRunDaily);
    return probe;
  }

  /** The daily contract canary (B-9) — lives HERE, never backtest-service. */
  @Bean
  public in.arthayantra.marketdata.kite.canary.ContractCanary contractCanary(
      org.springframework.web.client.RestClient.Builder restClientBuilder,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.AccessTokenProvider tokenProvider,
      in.arthayantra.marketdata.kite.KiteCallExecutor executor,
      org.springframework.data.redis.core.StringRedisTemplate redis,
      in.arthayantra.marketdata.alerts.NtfyClient ntfy,
      in.arthayantra.marketcalendar.MarketCalendar calendar,
      java.time.Clock clock,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    return new in.arthayantra.marketdata.kite.canary.ContractCanary(
        restClientBuilder,
        properties.baseUrl(),
        properties.resolveApiKey(),
        tokenProvider,
        executor,
        redis,
        ntfy,
        calendar,
        clock,
        objectMapper,
        meterRegistry);
  }

  /**
   * Live ticker feed (Phase 13): KiteTicker behind the TickerHandle seam, created lazily at
   * start because the day's token cannot exist before the morning ritual.
   */
  @Bean
  public MarketFeed liveMarketFeed(
      in.arthayantra.marketdata.kite.ticker.SubscriptionRegistry registry,
      in.arthayantra.marketdata.kite.LastSeenProvider lastSeenProvider,
      in.arthayantra.marketdata.kite.GapBackfiller gapBackfiller,
      in.arthayantra.marketcalendar.MarketCalendar calendar,
      io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakers,
      java.time.Clock clock,
      io.micrometer.core.instrument.MeterRegistry meterRegistry,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.AccessTokenProvider tokenProvider) {
    java.util.function.Supplier<in.arthayantra.marketdata.kite.ticker.TickerHandle> factory =
        () -> {
          String token =
              tokenProvider
                  .currentToken()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "no live Kite session yet — complete the morning ritual first"));
          return new KiteTickerHandle(token, properties.resolveApiKey());
        };
    return new in.arthayantra.marketdata.kite.ticker.LiveTickerFeed(
        factory,
        registry,
        lastSeenProvider,
        gapBackfiller,
        calendar,
        circuitBreakers.circuitBreaker("kite-ticker"),
        clock,
        meterRegistry);
  }

  /**
   * Live batched quotes through the QUOTE limiter family (Phase 15; WireMock-tested). Default
   * source; an OpenAlgo swap (plan §3/§4) is selected per capability by
   * {@code artha.marketdata.source.quotes=openalgo} ({@link in.arthayantra.marketdata.openalgo.live.OpenAlgoConfig}).
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "artha.marketdata.source.quotes",
      havingValue = "kite",
      matchIfMissing = true)
  public QuoteGateway liveQuoteGateway(
      org.springframework.web.client.RestClient.Builder restClientBuilder,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.AccessTokenProvider tokenProvider,
      in.arthayantra.marketdata.kite.KiteCallExecutor executor,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      @org.springframework.beans.factory.annotation.Value("${artha.kite.quote-batch-size:250}")
          int quoteBatchSize) {
    return new LiveQuoteGateway(
        restClientBuilder,
        properties.baseUrl(),
        properties.resolveApiKey(),
        tokenProvider,
        executor,
        objectMapper,
        quoteBatchSize);
  }

  /**
   * Live historical fetch through the rate-limited executor (Phase 11; WireMock-tested). Default
   * source; {@code artha.marketdata.source.candles=openalgo} swaps in the OpenAlgo adapter (§3/§4).
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "artha.marketdata.source.candles",
      havingValue = "kite",
      matchIfMissing = true)
  public HistoricalCandleGateway liveHistoricalCandleGateway(
      org.springframework.web.client.RestClient.Builder restClientBuilder,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.AccessTokenProvider tokenProvider,
      in.arthayantra.marketdata.kite.InstrumentTokenResolver tokenResolver,
      in.arthayantra.marketdata.kite.KiteCallExecutor executor,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    return new LiveHistoricalCandleGateway(
        restClientBuilder,
        properties.baseUrl(),
        properties.resolveApiKey(),
        tokenProvider,
        tokenResolver,
        executor,
        objectMapper);
  }

  /** Live dump download over the Kite wire format, under the kite-dump budget (B-3). */
  @Bean
  public InstrumentDumpGateway liveInstrumentDumpGateway(
      org.springframework.web.client.RestClient.Builder restClientBuilder,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.AccessTokenProvider tokenProvider,
      in.arthayantra.marketdata.kite.KiteCallExecutor executor) {
    return new LiveInstrumentDumpGateway(
        restClientBuilder, properties.baseUrl(), properties.resolveApiKey(), tokenProvider, executor);
  }

  // the Phase-12 KiteSessionStore bean above IS the live AccessTokenProvider (D13:
  // the token never leaves this service) — no placeholder provider remains
}
