package in.arthayantra.marketdata.openalgo.live;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAlgo appliance settings (plan §2/§3). The base URL is configurable so WireMock can stand in
 * for the {@code openalgo} container in tests. REST capture speaks the wire format through
 * {@link org.springframework.web.client.RestClient} rather than the SDK — not because the SDK's base
 * URL is pinned (it is settable, unlike Kite's) but for parity with the Kite wire pattern (typed
 * DTOs + contract-drift canary; the SDK returns untyped {@code JsonObject}). The SDK
 * ({@code in.openalgo:openalgo}, MIT) is reserved for the WS + order paths (plan §17.2).
 *
 * <p>The OpenAlgo API key is OpenAlgo's OWN generated key (NOT the fronted broker's secret); it is
 * mounted as a Docker secret file and read on demand (never logged).
 */
@ConfigurationProperties(prefix = "artha.openalgo")
public record OpenAlgoProperties(String baseUrl, String apiKeyFile, String apiKey) {

  /** Defaults: the loopback {@code openalgo} compose service, Docker secret file. */
  public OpenAlgoProperties {
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
