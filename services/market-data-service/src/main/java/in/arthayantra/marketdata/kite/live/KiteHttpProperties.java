package in.arthayantra.marketdata.kite.live;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live Kite wire settings. The base URL is configurable so WireMock can stand in for
 * {@code api.kite.trade} in tests — the reason the REST adapters speak the wire format through
 * {@link org.springframework.web.client.RestClient} rather than the SDK (whose base URL is
 * pinned); the SDK is retained for the binary {@code KiteTicker} WS path (Phase 13).
 */
@ConfigurationProperties(prefix = "artha.kite")
public record KiteHttpProperties(
    String baseUrl,
    String apiKeyFile,
    String apiSecretFile,
    String apiKey,
    String masterKeyFile,
    String loginUrlBase) {

  /** Defaults: real Kite, Docker secret files. */
  public KiteHttpProperties {
    baseUrl = baseUrl == null ? "https://api.kite.trade" : baseUrl;
    apiKeyFile = apiKeyFile == null ? "/run/secrets/kite_api_key" : apiKeyFile;
    apiSecretFile = apiSecretFile == null ? "/run/secrets/kite_api_secret" : apiSecretFile;
    masterKeyFile = masterKeyFile == null ? "/run/secrets/artha_master_key" : masterKeyFile;
    loginUrlBase = loginUrlBase == null ? "https://kite.zerodha.com/connect/login" : loginUrlBase;
  }

  /** The 256-bit AES-GCM master key, base64 in its secret file (Phase 12). */
  public byte[] resolveMasterKey() {
    return java.util.Base64.getDecoder().decode(readSecret(Path.of(masterKeyFile)));
  }

  /** The API key — explicit property (tests) or the secret file. */
  public String resolveApiKey() {
    if (apiKey != null && !apiKey.isBlank()) {
      return apiKey;
    }
    return readSecret(Path.of(apiKeyFile));
  }

  /** The API secret from its secret file. */
  public String resolveApiSecret() {
    return readSecret(Path.of(apiSecretFile));
  }

  private static String readSecret(Path file) {
    try {
      return Files.readString(file).trim();
    } catch (IOException e) {
      throw new UncheckedIOException("missing Kite secret file " + file, e);
    }
  }
}
