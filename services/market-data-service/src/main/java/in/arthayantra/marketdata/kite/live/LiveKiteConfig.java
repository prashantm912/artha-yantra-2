package in.arthayantra.marketdata.kite.live;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.SessionGateway;
import in.arthayantra.marketdata.kite.TickListener;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Live-profile wiring (A.7a): the context FAILS FAST at startup when Kite secrets are absent
 * (COMMON §10.3 — never a half-configured live stack), and every port is a stub throwing
 * 503 {@code NOT_CONFIGURED} until the real adapters land in Stage B (Phases 9–13).
 */
@Configuration
@Profile("live")
public class LiveKiteConfig {

  @Value("${artha.kite.api-key-file:/run/secrets/kite_api_key}")
  private Path apiKeyFile;

  @Value("${artha.kite.api-secret-file:/run/secrets/kite_api_secret}")
  private Path apiSecretFile;

  /** D13: live without credentials is a startup error, not a degraded runtime. */
  @PostConstruct
  void failFastWithoutCredentials() {
    if (!Files.isReadable(apiKeyFile) || !Files.isReadable(apiSecretFile)) {
      throw new IllegalStateException(
          "live profile requires Kite credentials as Docker secret files ("
              + apiKeyFile
              + ", "
              + apiSecretFile
              + ") — see deploy/secrets/README.md (D13); mock mode needs none");
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

  /** Stage-B stub. */
  @Bean
  public InstrumentDumpGateway liveInstrumentDumpGateway() {
    return () -> {
      throw notConfigured("InstrumentDumpGateway");
    };
  }
}
