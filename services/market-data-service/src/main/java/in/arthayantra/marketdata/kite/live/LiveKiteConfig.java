package in.arthayantra.marketdata.kite.live;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.SessionGateway;
import in.arthayantra.marketdata.kite.TickListener;
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
 * Live-profile wiring (A.7a): the context FAILS FAST at startup when Kite secrets are absent
 * (COMMON §10.3 — never a half-configured live stack), and every port is a stub throwing
 * 503 {@code NOT_CONFIGURED} until the real adapters land in Stage B (Phases 9–13).
 */
@Configuration
@Profile("live")
@org.springframework.boot.context.properties.EnableConfigurationProperties(KiteHttpProperties.class)
public class LiveKiteConfig {

  /**
   * D13: live without credentials is a startup error, not a degraded runtime. Implemented as a
   * static {@link BeanFactoryPostProcessor} so it fires BEFORE any bean instantiation —
   * deterministic ordering ahead of datasource/repository startup work.
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
      if (!isNonBlankFile(apiKeyFile) || !isNonBlankFile(apiSecretFile)) {
        throw new IllegalStateException(
            "live profile requires Kite credentials as Docker secret files ("
                + apiKeyFile
                + ", "
                + apiSecretFile
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

  private static ApiException notConfigured(String port) {
    return new ApiException(
        503, ErrorCodes.NOT_CONFIGURED, "live " + port + " adapter lands in Stage B");
  }

  /** Stage-B stub. */
  @Bean
  public SessionGateway liveSessionGateway() {
    return new SessionGateway() {
      @Override
      public boolean sessionActive() {
        throw notConfigured("SessionGateway");
      }

      @Override
      public String statusLabel() {
        return "LIVE";
      }
    };
  }

  /** Stage-B stub. */
  @Bean
  public MarketFeed liveMarketFeed() {
    return new MarketFeed() {
      @Override
      public void start(TickListener listener) {
        throw notConfigured("MarketFeed");
      }

      @Override
      public void stop() {}

      @Override
      public boolean running() {
        return false;
      }
    };
  }

  /** Stage-B stub. */
  @Bean
  public QuoteGateway liveQuoteGateway() {
    return keys -> {
      throw notConfigured("QuoteGateway");
    };
  }

  /** Stage-B stub. */
  @Bean
  public HistoricalCandleGateway liveHistoricalCandleGateway() {
    return (key, interval, from, to) -> {
      throw notConfigured("HistoricalCandleGateway");
    };
  }

  /** Live dump download over the Kite wire format (Phase 9; WireMock-tested). */
  @Bean
  public InstrumentDumpGateway liveInstrumentDumpGateway(
      org.springframework.web.client.RestClient.Builder restClientBuilder,
      KiteHttpProperties properties,
      in.arthayantra.marketdata.kite.AccessTokenProvider tokenProvider) {
    return new LiveInstrumentDumpGateway(
        restClientBuilder, properties.baseUrl(), properties.resolveApiKey(), tokenProvider);
  }

  /**
   * Placeholder token provider — the store-backed implementation lands with the OAuth lifecycle
   * (Phase 12). Until then live adapters report an expired session rather than a missing bean.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
      in.arthayantra.marketdata.kite.AccessTokenProvider.class)
  public in.arthayantra.marketdata.kite.AccessTokenProvider liveAccessTokenProvider() {
    return java.util.Optional::empty;
  }
}
