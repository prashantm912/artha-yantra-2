package in.arthayantra.strategysignal.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAlgo appliance settings for the strategy-signal order-READ surface (§18.1) — the twin of
 * market-data's {@code OpenAlgoProperties}. The base URL is configurable so WireMock /
 * {@code MockRestServiceServer} can stand in for the {@code openalgo} container in tests. The
 * OpenAlgo API key is OpenAlgo's OWN generated key (NOT the fronted broker's secret); it is mounted
 * as a Docker secret file and read on demand (never logged).
 */
@ConfigurationProperties(prefix = "artha.openalgo")
public record OpenAlgoOrderProperties(String baseUrl, String apiKeyFile, String apiKey) {

  /** Defaults: the loopback {@code openalgo} compose service, Docker secret file. */
  public OpenAlgoOrderProperties {
    baseUrl = baseUrl == null ? "http://openalgo:5000" : baseUrl;
    apiKeyFile = apiKeyFile == null ? "/run/secrets/openalgo_api_key" : apiKeyFile;
  }

  /** The OpenAlgo API key — explicit property (tests) or the secret file. */
  public String resolveApiKey() {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey;
    }
    return readSecret(Path.of(apiKeyFile));
  }

  private static String readSecret(Path file) {
    try {
      return Files.readString(file).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("missing OpenAlgo API key secret file " + file, e);
    }
  }
}
