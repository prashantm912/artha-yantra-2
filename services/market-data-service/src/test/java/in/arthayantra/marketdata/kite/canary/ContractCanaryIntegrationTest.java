package in.arthayantra.marketdata.kite.canary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.kite.session.KiteSessionStore;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase-16 canary IT (live profile vs WireMock, ntfy captured on the same server):
 * fixture-faithful responses → ZERO drift; a removed field → critical drift + ntfy POST; an added
 * field → warning; the Redis daily-once marker makes the second run of a day a no-op.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=live",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.ntfy.topic=ay-test-topic"
    })
class ContractCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
  private static final Path SECRETS_DIR;

  static {
    WIREMOCK.start();
    try {
      SECRETS_DIR = Files.createTempDirectory("canary-secrets");
      Files.writeString(SECRETS_DIR.resolve("kite_api_key"), "test-key");
      Files.writeString(SECRETS_DIR.resolve("kite_api_secret"), "test-secret");
      byte[] key = new byte[32];
      Files.writeString(
          SECRETS_DIR.resolve("artha_master_key"), Base64.getEncoder().encodeToString(key));
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void kiteProperties(DynamicPropertyRegistry registry) {
    registry.add("artha.kite.base-url", WIREMOCK::baseUrl);
    registry.add("artha.ntfy.url", WIREMOCK::baseUrl);
    registry.add("artha.kite.api-key-file", () -> SECRETS_DIR.resolve("kite_api_key").toString());
    registry.add(
        "artha.kite.api-secret-file", () -> SECRETS_DIR.resolve("kite_api_secret").toString());
    registry.add(
        "artha.kite.master-key-file", () -> SECRETS_DIR.resolve("artha_master_key").toString());
  }

  private static final String PROFILE_BODY =
      "{\"status\":\"success\",\"data\":{\"user_id\":\"AB1234\",\"user_name\":\"Owner\","
          + "\"email\":\"o@x.in\"}}";
  private static final String QUOTE_BODY =
      "{\"status\":\"success\",\"data\":{\"NSE:NIFTY 50\":{\"last_price\":24137.55,"
          + "\"volume\":1,\"oi\":0,\"depth\":{\"buy\":[],\"sell\":[]}}}}";
  private static final String HISTORICAL_BODY =
      "{\"status\":\"success\",\"data\":{\"candles\":[]}}";
  private static final String DUMP_HEADER =
      "instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,lot_size,"
          + "tick_size,instrument_type,segment,exchange\n";

  @Autowired private ContractCanary canary;
  @Autowired private KiteSessionStore store;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void stubFaithfulFixtures() {
    WIREMOCK.resetAll();
    store.store("canary-token", "AB1234");
    redis.delete(ContractCanary.MARKER_KEY_PREFIX + LocalDate.now(java.time.ZoneOffset.UTC));
    redis.delete(
        ContractCanary.MARKER_KEY_PREFIX + LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/user/profile")).willReturn(aResponse().withBody(PROFILE_BODY)));
    WIREMOCK.stubFor(get(urlPathEqualTo("/quote")).willReturn(aResponse().withBody(QUOTE_BODY)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/instruments/historical/256265/minute"))
            .willReturn(aResponse().withBody(HISTORICAL_BODY)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/instruments/NSE")).willReturn(aResponse().withBody(DUMP_HEADER)));
    WIREMOCK.stubFor(post(urlPathEqualTo("/ay-test-topic")).willReturn(aResponse().withStatus(200)));
  }

  @Test
  void fixtureFaithfulResponsesProduceZeroDrift() {
    ContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).isEmpty();
    assertThat(result.lastContractCheck()).isNotNull();
    WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
    assertThat(redis.opsForValue().get("kite:contract:check")).contains("\"drift\":[]");
  }

  @Test
  void removedFieldIsCriticalDriftWithNtfyAlert() {
    // the quote loses last_price — the SDK's Gson would silently swallow this
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/quote"))
            .willReturn(
                aResponse()
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"NSE:NIFTY 50\":{"
                            + "\"volume\":1,\"oi\":0,\"depth\":{}}}}")));

    ContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:quote.data.*.last_price");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void addedFieldIsWarningNotCritical() {
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/user/profile"))
            .willReturn(
                aResponse()
                    .withBody(
                        "{\"status\":\"success\",\"new_block\":{},\"data\":{\"user_id\":\"A\","
                            + "\"user_name\":\"B\",\"email\":\"c@d\"}}")));

    ContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("NEW:profile.new_block");
    assertThat(result.drift()).noneMatch(d -> d.startsWith("MISSING:"));
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void dailyOnceMarkerMakesTheSecondRunANoOp() {
    canary.maybeRunDaily();
    int callsAfterFirst = WIREMOCK.getAllServeEvents().size();

    canary.maybeRunDaily();

    assertThat(WIREMOCK.getAllServeEvents())
        .as("idempotent per trading day via the Redis marker")
        .hasSize(callsAfterFirst);
  }
}
