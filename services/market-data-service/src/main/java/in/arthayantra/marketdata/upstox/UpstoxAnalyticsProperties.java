package in.arthayantra.marketdata.upstox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstox Market-Information analytics settings (ADR-0002). A <b>dedicated, long-lived analytics
 * access token</b> — strictly SEPARATE from any live execution session — read from a Docker secret
 * FILE (never an env var, never logged), mirroring the Kite/OpenAlgo secret-file convention. The
 * base URL is configurable so WireMock can stand in for {@code api.upstox.com} in tests.
 */
@ConfigurationProperties(prefix = "artha.upstox.analytics")
public record UpstoxAnalyticsProperties(String baseUrl, String tokenFile, String token) {

  /** Defaults: real Upstox, the analytics-token Docker secret file. */
  public UpstoxAnalyticsProperties {
    baseUrl = baseUrl == null ? "https://api.upstox.com" : baseUrl;
    tokenFile = tokenFile == null ? "/run/secrets/upstox_analytics_token" : tokenFile;
  }

  /** The analytics bearer token — explicit property (tests) or the secret file. */
  public String resolveToken() {
    if (token != null && !token.isBlank()) {
      return token;
    }
    try {
      return Files.readString(Path.of(tokenFile)).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("missing Upstox analytics token file " + tokenFile, e);
    }
  }
}
