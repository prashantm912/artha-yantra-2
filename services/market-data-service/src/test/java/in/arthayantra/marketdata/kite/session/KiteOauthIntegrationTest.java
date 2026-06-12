package in.arthayantra.marketdata.kite.session;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Phase-12 IT under the LIVE profile against WireMock: full callback exchange (request_token to
 * access_token with SHA-256 checksum), token persisted ENCRYPTED, restart-reload decrypts and
 * resumes, expiry flips status + clears the in-memory token (retry suppression: re-login is the
 * cure), delete invalidates, and a rotated master key degrades gracefully.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=live",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class KiteOauthIntegrationTest extends MarketDataIntegrationTestBase {

  static final byte[] MASTER_KEY = new byte[32];

  static {
    for (int i = 0; i < 32; i++) {
      MASTER_KEY[i] = (byte) (7 + i);
    }
  }

  private static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
  private static final Path SECRETS_DIR;

  static {
    WIREMOCK.start();
    try {
      SECRETS_DIR = Files.createTempDirectory("kite-secrets");
      Files.writeString(SECRETS_DIR.resolve("kite_api_key"), "test-key");
      Files.writeString(SECRETS_DIR.resolve("kite_api_secret"), "test-secret");
      Files.writeString(
          SECRETS_DIR.resolve("artha_master_key"), Base64.getEncoder().encodeToString(MASTER_KEY));
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void kiteProperties(DynamicPropertyRegistry registry) {
    registry.add("artha.kite.base-url", WIREMOCK::baseUrl);
    registry.add("artha.kite.api-key-file", () -> SECRETS_DIR.resolve("kite_api_key").toString());
    registry.add(
        "artha.kite.api-secret-file", () -> SECRETS_DIR.resolve("kite_api_secret").toString());
    registry.add(
        "artha.kite.master-key-file", () -> SECRETS_DIR.resolve("artha_master_key").toString());
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private KiteSessionStore store;
  @Autowired private KiteSessionRepository repository;
  @Autowired private AesGcmTokenCipher cipher;
  @Autowired private SessionHealthProbe probe;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void cleanSlate() {
    WIREMOCK.resetAll();
    store.clear();
  }

  /** MockMvc GET (the WireMock static import owns the bare name). */
  private static MockHttpServletRequestBuilder httpGet(String uri) {
    return MockMvcRequestBuilders.get(uri);
  }

  private static String sha256Hex(String input) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void fullCallbackExchangePersistsEncryptedAndFlipsStatus() throws Exception {
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/session/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"access_token\":\"fake-access-token-123\","
                            + "\"user_id\":\"AB1234\"}}")));

    mockMvc
        .perform(httpGet("/api/v1/auth/kite/callback").param("request_token", "req-tok-1"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("postMessage")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("https://127.0.0.1:8080")));

    // the wire carried the SHA-256(api_key + request_token + api_secret) checksum
    String checksum = sha256Hex("test-key" + "req-tok-1" + "test-secret");
    WIREMOCK.verify(
        postRequestedFor(urlPathEqualTo("/session/token"))
            .withRequestBody(containing("api_key=test-key"))
            .withRequestBody(containing("request_token=req-tok-1"))
            .withRequestBody(containing("checksum=" + checksum)));

    // persisted ENCRYPTED: ciphertext never contains the plaintext, but decrypts under the key
    KiteSessionRepository.SessionRow row = repository.find().orElseThrow();
    assertThat(new String(row.tokenCiphertext(), StandardCharsets.UTF_8))
        .doesNotContain("fake-access-token");
    assertThat(cipher.decrypt(row.tokenCiphertext(), row.nonce()))
        .isEqualTo("fake-access-token-123");
    assertThat(row.kiteUserId()).isEqualTo("AB1234");

    mockMvc
        .perform(httpGet("/api/v1/auth/kite/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connected").value(true))
        .andExpect(jsonPath("$.profile").value("LIVE"))
        .andExpect(jsonPath("$.kiteUserId").value("AB1234"))
        .andExpect(jsonPath("$.tokenValidUntil").isString());

    assertThat(redis.opsForValue().get(SessionStatusPublisher.STATUS_KEY)).isEqualTo("CONNECTED");
  }

  @Test
  void restartReloadDecryptsAndResumesWithoutRelogin() {
    store.store("fake-access-token-456", "AB1234");

    // a fresh store over the same row = the container restart path
    KiteSessionStore rebooted = new KiteSessionStore(repository, cipher);
    rebooted.loadFromDatabase();

    assertThat(rebooted.currentToken()).contains("fake-access-token-456");
    assertThat(rebooted.state()).isEqualTo(KiteSessionStore.State.CONNECTED);
    assertThat(rebooted.kiteUserId()).isEqualTo("AB1234");
  }

  @Test
  void rotatedMasterKeyDegradesToDisconnectedNotCrash() {
    store.store("fake-access-token-789", "AB1234");

    byte[] otherKey = new byte[32];
    KiteSessionStore wrongKeyStore =
        new KiteSessionStore(repository, new AesGcmTokenCipher(otherKey));
    wrongKeyStore.loadFromDatabase();

    assertThat(wrongKeyStore.currentToken()).isEmpty();
    assertThat(wrongKeyStore.state()).isEqualTo(KiteSessionStore.State.DISCONNECTED);
  }

  @Test
  void expiryFlipsStatusClearsTokenAndSuppressesCalls() throws Exception {
    store.store("stale-token", "AB1234");
    WIREMOCK.stubFor(get(urlPathEqualTo("/user/profile")).willReturn(aResponse().withStatus(403)));

    KiteSessionStore.State state = probe.probeNow();

    assertThat(state).isEqualTo(KiteSessionStore.State.TOKEN_EXPIRED);
    assertThat(store.currentToken()).as("expired token short-circuits outbound calls").isEmpty();
    assertThat(redis.opsForValue().get(SessionStatusPublisher.STATUS_KEY))
        .isEqualTo("TOKEN_EXPIRED");
    assertThat(WIREMOCK.getAllServeEvents())
        .as("one probe, no retries — re-login is the cure")
        .hasSize(1);

    mockMvc
        .perform(httpGet("/api/v1/auth/kite/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connected").value(false))
        .andExpect(jsonPath("$.state").value("TOKEN_EXPIRED"));
  }

  @Test
  void healthyProbeStampsValidation() {
    store.store("good-token", "AB1234");
    WIREMOCK.stubFor(get(urlPathEqualTo("/user/profile")).willReturn(aResponse().withStatus(200)));

    assertThat(probe.probeNow()).isEqualTo(KiteSessionStore.State.CONNECTED);
    assertThat(repository.find().orElseThrow().lastValidatedAt()).isNotNull();
    assertThat(redis.opsForValue().get(SessionStatusPublisher.STATUS_KEY)).isEqualTo("CONNECTED");
  }

  @Test
  void deleteInvalidatesTheStoredToken() throws Exception {
    store.store("doomed-token", "AB1234");

    mockMvc.perform(delete("/api/v1/auth/kite/session")).andExpect(status().isNoContent());

    assertThat(repository.find()).isEmpty();
    assertThat(store.currentToken()).isEmpty();
    assertThat(redis.opsForValue().get(SessionStatusPublisher.STATUS_KEY))
        .isEqualTo("DISCONNECTED");
  }

  @Test
  void loginUrlEmbedsTheApiKey() throws Exception {
    mockMvc
        .perform(httpGet("/api/v1/auth/kite/login-url"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.url")
                .value("https://kite.zerodha.com/connect/login?v=3&api_key=test-key"));
  }
}
