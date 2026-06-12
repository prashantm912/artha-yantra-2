package in.arthayantra.marketdata.kite.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.session.SessionWireClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Kite session calls over the wire format (WireMock-testable): {@code POST /session/token} with
 * {@code checksum = SHA-256(api_key + request_token + api_secret)} and the {@code GET
 * /user/profile} probe. The api_secret is read per call from its Docker secret file and never
 * retained or logged.
 */
public class LiveSessionWireClient implements SessionWireClient {

  private static final Logger log = LoggerFactory.getLogger(LiveSessionWireClient.class);

  private final RestClient restClient;
  private final KiteHttpProperties properties;
  private final ObjectMapper objectMapper;

  /** Wires the wire client. */
  public LiveSessionWireClient(
      RestClient.Builder builder, KiteHttpProperties properties, ObjectMapper objectMapper) {
    this.restClient = builder.baseUrl(properties.baseUrl()).build();
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  public TokenSession exchange(String requestToken) {
    String apiKey = properties.resolveApiKey();
    String checksum = sha256Hex(apiKey + requestToken + properties.resolveApiSecret());
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("api_key", apiKey);
    form.add("request_token", requestToken);
    form.add("checksum", checksum);
    String body;
    try {
      body =
          restClient
              .post()
              .uri("/session/token")
              .header("X-Kite-Version", "3")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(String.class);
    } catch (HttpClientErrorException rejected) {
      // single-use token spent/expired/forged — re-login is the only cure, never retry
      throw new ApiException(
          401,
          ErrorCodes.KITE_TOKEN_EXPIRED,
          "Kite rejected the request token (" + rejected.getStatusCode().value() + ")");
    }
    try {
      JsonNode data = objectMapper.readTree(body).path("data");
      String accessToken = data.path("access_token").asText("");
      if (accessToken.isEmpty()) {
        throw new IllegalStateException("no access_token in exchange response");
      }
      return new TokenSession(accessToken, data.path("user_id").asText(null));
    } catch (Exception e) {
      throw new ApiException(
          502, ErrorCodes.KITE_UPSTREAM_ERROR, "session exchange parse failed: " + e.getMessage());
    }
  }

  @Override
  public ProbeResult probe(String accessToken) {
    try {
      restClient
          .get()
          .uri("/user/profile")
          .header("X-Kite-Version", "3")
          .header("Authorization", "token " + properties.resolveApiKey() + ":" + accessToken)
          .retrieve()
          .toBodilessEntity();
      return ProbeResult.VALID;
    } catch (HttpClientErrorException auth) {
      return auth.getStatusCode().value() == 401 || auth.getStatusCode().value() == 403
          ? ProbeResult.EXPIRED
          : ProbeResult.ERROR;
    } catch (Exception transientFailure) {
      log.warn("kite session probe errored: {}", transientFailure.getMessage());
      return ProbeResult.ERROR;
    }
  }

  private static String sha256Hex(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
