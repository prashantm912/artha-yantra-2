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
@org.springframework.boot.context.properties.EnableConfigurationProperties({
  KiteHttpProperties.class,
  in.arthayantra.marketdata.kite.session.autologin.KiteAutoLoginProperties.class
})
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
      // ⚠️ The three auto-login secrets are required ONLY when the feature is armed. The live
      // stack must still boot without them while the flag is off (which is the default), or
      // shipping this feature would break every existing live start — so this check is INSIDE
      // the flag, deliberately, rather than joined onto the unconditional set above.
      if (!Boolean.TRUE.equals(
          environment.getProperty("artha.kite.auto-login.enabled", Boolean.class, Boolean.FALSE))) {
        return;
      }
      Path userIdFile =
          Path.of(
              environment.getProperty(
                  "artha.kite.auto-login.user-id-file", "/run/secrets/kite_user_id"));
      Path passwordFile =
          Path.of(
              environment.getProperty(
                  "artha.kite.auto-login.password-file", "/run/secrets/kite_password"));
      Path totpSeedFile =
          Path.of(
              environment.getProperty(
                  "artha.kite.auto-login.totp-seed-file", "/run/secrets/kite_totp_seed"));
      if (!isNonBlankFile(userIdFile)
          || !isNonBlankFile(passwordFile)
          || !isNonBlankFile(totpSeedFile)) {
        throw new IllegalStateException(
            "artha.kite.auto-login.enabled=true requires the auto-login secret files ("
                + userIdFile
                + ", "
                + passwordFile
                + ", "
                + totpSeedFile
                + ") — see deploy/secrets/README.md; disable the flag to boot without them");
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
      in.arthayantra.marketdata.kite.session.AesGcmTokenCipher cipher,
      java.time.Clock clock) {
    var store =
        new in.arthayantra.marketdata.kite.session.KiteSessionStore(repository, cipher, clock);
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

  /**
   * The undocumented browser leg of the daily login — bound ONLY when the feature is armed.
   *
   * <p>Default OFF: with {@code artha.kite.auto-login.enabled} unset, neither this bean nor
   * {@link #kiteAutoLoginService} exists, so no {@code @Scheduled} is registered and no credential
   * file is ever opened. Bean absence is the arming gate, which is a stronger off than an internal
   * boolean — there is nothing to accidentally invoke.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "artha.kite.auto-login.enabled",
      havingValue = "true")
  public in.arthayantra.marketdata.kite.session.autologin.LoginWireClient loginWireClient(
      in.arthayantra.marketdata.kite.session.autologin.KiteAutoLoginProperties autoLogin,
      KiteHttpProperties properties,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      java.time.Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${artha.kite.auto-login.connect-timeout:5s}")
          java.time.Duration connectTimeout,
      @org.springframework.beans.factory.annotation.Value("${artha.kite.auto-login.read-timeout:20s}")
          java.time.Duration readTimeout) {
    // ⚠️ Resolved and ORIGIN-PINNED here, at bean creation, so a hostile or fat-fingered
    // override fails the CONTEXT at boot rather than posting credentials somewhere unintended at
    // 08:05 (cross-vendor review Critical 1). LoginEndpoints.pinned is the only production entry.
    return new in.arthayantra.marketdata.kite.session.autologin.LiveLoginWireClient(
        autoLogin,
        in.arthayantra.marketdata.kite.session.autologin.LoginEndpoints.pinned(autoLogin),
        objectMapper,
        clock,
        properties.resolveApiKey(),
        connectTimeout,
        readTimeout);
  }

  /**
   * The morning login orchestrator + its silence watchdog — bound ONLY when the feature is armed.
   *
   * <p>Takes {@code monitorTaskScheduler} by name for the delayed transport-only re-attempt, the
   * same pool its two {@code @Scheduled} methods name, so a re-attempt cannot be queued behind a
   * hung batch job on the shared default pool.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "artha.kite.auto-login.enabled",
      havingValue = "true")
  public in.arthayantra.marketdata.kite.session.autologin.KiteAutoLoginService kiteAutoLoginService(
      in.arthayantra.marketdata.kite.session.autologin.LoginWireClient loginWireClient,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
      in.arthayantra.marketdata.kite.session.KiteSessionService sessionService,
      in.arthayantra.marketdata.kite.session.KiteSessionStore store,
      in.arthayantra.marketdata.alerts.NtfyClient ntfy,
      in.arthayantra.marketcalendar.MarketCalendar calendar,
      java.time.Clock clock,
      @org.springframework.beans.factory.annotation.Qualifier("monitorTaskScheduler")
          org.springframework.scheduling.TaskScheduler taskScheduler,
      io.micrometer.core.instrument.MeterRegistry meterRegistry,
      @org.springframework.beans.factory.annotation.Value("${artha.kite.auto-login.retry-delay:2m}")
          java.time.Duration retryDelay) {
    return new in.arthayantra.marketdata.kite.session.autologin.KiteAutoLoginService(
        loginWireClient,
        // Durable per-IST-day terminal marker over the existing canary_runs claim idiom — no
        // migration, no schema change (review round 3).
        in.arthayantra.marketdata.kite.session.autologin.AutoLoginTerminalLedger.forService(
            jdbcTemplate, clock),
        sessionService,
        store,
        ntfy,
        calendar,
        clock,
        taskScheduler,
        meterRegistry,
        retryDelay);
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
   * start because the day's token cannot exist before the morning ritual. Default ticker source;
   * {@code artha.marketdata.source.ticker=upstox} swaps in the direct-Upstox v3 WS feed (Wave U3,
   * {@code kite.upstoxfeed.UpstoxMarketFeedConfig}) — Kite stays the default until the §17.3
   * scalp-latency gate is green and the owner flips it. Exactly ONE {@link MarketFeed} binds (the
   * two are mutually-exclusive {@code @ConditionalOnProperty} branches).
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "artha.marketdata.source.ticker",
      havingValue = "kite",
      matchIfMissing = true)
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
