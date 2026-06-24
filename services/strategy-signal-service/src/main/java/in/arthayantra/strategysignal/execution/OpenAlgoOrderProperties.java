package in.arthayantra.strategysignal.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAlgo appliance settings for the strategy-signal order surface — the §18.1 order-READ
 * (orderbook/positions/tradebook/funds) and the §17.3 order-WRITE ({@code /api/v1/placeorder})
 * paths. The twin of market-data's {@code OpenAlgoProperties}. The base URL is configurable so
 * WireMock / {@code MockRestServiceServer} can stand in for the {@code openalgo} container in tests.
 * The OpenAlgo API key is OpenAlgo's OWN generated key (NOT the fronted broker's secret); it is
 * mounted as a Docker secret file and read on demand (never logged). {@code strategy} is the tag
 * OpenAlgo stamps on every placed order (it logs/routes by it).
 */
@ConfigurationProperties(prefix = "artha.openalgo")
public record OpenAlgoOrderProperties(
    String baseUrl, String apiKeyFile, String apiKey, String strategy) {

  /** Defaults: the loopback {@code openalgo} compose service, Docker secret file, ArthaYantra tag. */
  public OpenAlgoOrderProperties {
    baseUrl = baseUrl == null ? "http://openalgo:5000" : baseUrl;
    apiKeyFile = apiKeyFile == null ? "/run/secrets/openalgo_api_key" : apiKeyFile;
    strategy = strategy == null || strategy.isBlank() ? "ArthaYantra" : strategy;
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
