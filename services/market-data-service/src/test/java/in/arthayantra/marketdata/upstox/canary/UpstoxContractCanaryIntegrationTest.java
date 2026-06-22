package in.arthayantra.marketdata.upstox.canary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Upstox canary IT (live profile vs WireMock, ntfy captured on the same server), twin of {@code
 * OpenAlgoContractCanaryIntegrationTest}: faithful responses -&gt; ZERO drift; a removed consumed
 * {@code buy_amount} -&gt; critical drift + ntfy POST; an added top-level block -&gt; warning; the
 * Redis daily-once marker makes the second run of a day a no-op.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=live",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.ntfy.topic=ay-test-topic",
      "artha.upstox.canary-enabled=true"
    })
class UpstoxContractCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
  private static final Path SECRETS_DIR;

  static {
    WIREMOCK.start();
    try {
      SECRETS_DIR = Files.createTempDirectory("upstox-canary-secrets");
      Files.writeString(SECRETS_DIR.resolve("kite_api_key"), "test-key");
      Files.writeString(SECRETS_DIR.resolve("kite_api_secret"), "test-secret");
      Files.writeString(SECRETS_DIR.resolve("openalgo_api_key"), "test-openalgo-key");
      Files.writeString(SECRETS_DIR.resolve("upstox_analytics_token"), "test-upstox-token");
      byte[] key = new byte[32];
      Files.writeString(
          SECRETS_DIR.resolve("artha_master_key"), Base64.getEncoder().encodeToString(key));
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("artha.kite.base-url", WIREMOCK::baseUrl);
    registry.add("artha.ntfy.url", WIREMOCK::baseUrl);
    registry.add("artha.kite.api-key-file", () -> SECRETS_DIR.resolve("kite_api_key").toString());
    registry.add(
        "artha.kite.api-secret-file", () -> SECRETS_DIR.resolve("kite_api_secret").toString());
    registry.add(
        "artha.kite.master-key-file", () -> SECRETS_DIR.resolve("artha_master_key").toString());
    registry.add("artha.openalgo.base-url", WIREMOCK::baseUrl);
    registry.add(
        "artha.openalgo.api-key-file", () -> SECRETS_DIR.resolve("openalgo_api_key").toString());
    registry.add("artha.upstox.analytics.base-url", WIREMOCK::baseUrl);
    registry.add(
        "artha.upstox.analytics.token-file",
        () -> SECRETS_DIR.resolve("upstox_analytics_token").toString());
  }

  private static final String FII_CASH =
      "{\"status\":\"success\",\"data\":{\"NSE_EQ|CASH\":[{\"time_stamp\":1750000000000,"
          + "\"buy_amount\":1.0,\"sell_amount\":1.0}]}}";
  private static final String FII_FNO =
      "{\"status\":\"success\",\"data\":{\"NSE_FO|INDEX_OPTIONS\":[{\"time_stamp\":1750000000000,"
          + "\"buy_amount\":1.0,\"sell_amount\":1.0,\"buy_contracts\":1}]}}";
  private static final String DII_CASH = FII_CASH;
  private static final String MAX_PAIN =
      "{\"status\":\"success\",\"data\":{\"instrument_key\":\"NSE_INDEX|Nifty 50\","
          + "\"expiry_date\":\"23-06-2026\",\"max_pain\":24100.0,\"spot_closing_price\":24087.2,"
          + "\"insights\":[{\"max_pain\":24100.0,\"spot_price\":24075.1,\"time\":\"09:15\"}]}}";
  private static final String PCR_OPT =
      "{\"status\":\"success\",\"data\":{\"instrument_key\":\"NSE_INDEX|Nifty 50\","
          + "\"expiry_date\":\"23-06-2026\",\"pcr\":0.8762,\"spot_closing_price\":24087.2,"
          + "\"insights\":[{\"pcr\":0.78,\"spot_price\":24075.1,\"time\":\"09:15\"}]}}";

  @Autowired private UpstoxContractCanary canary;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void stubFaithfulFixtures() {
    WIREMOCK.resetAll();
    redis.delete(UpstoxContractCanary.MARKER_KEY_PREFIX + LocalDate.now(ZoneId.of("Asia/Kolkata")));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(json(FII_CASH)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_FO|INDEX_OPTIONS"))
            .willReturn(json(FII_FNO)));
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/dii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(json(DII_CASH)));
    WIREMOCK.stubFor(get(urlPathEqualTo("/v2/market/max-pain")).willReturn(json(MAX_PAIN)));
    WIREMOCK.stubFor(get(urlPathEqualTo("/v2/market/pcr")).willReturn(json(PCR_OPT)));
    WIREMOCK.stubFor(post(urlPathEqualTo("/ay-test-topic")).willReturn(aResponse().withStatus(200)));
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  @Test
  void fixtureFaithfulResponsesProduceZeroDrift() {
    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).isEmpty();
    assertThat(result.lastContractCheck()).isNotNull();
    WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
    assertThat(redis.opsForValue().get(UpstoxContractCanary.RESULT_KEY)).contains("\"drift\":[]");
  }

  @Test
  void removedConsumedFieldIsCriticalDriftWithNtfyAlert() {
    // FII cash loses buy_amount — a removed CONSUMED field must surface as critical drift + ntfy.
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"data\":{\"NSE_EQ|CASH\":[{\"time_stamp\":1750000000000,"
                        + "\"sell_amount\":1.0}]}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:fii_cash.data.NSE_EQ|CASH.*.buy_amount");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void addedTopLevelFieldIsWarningNotCritical() {
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .willReturn(
                json(
                    "{\"status\":\"success\",\"new_block\":{\"x\":1},\"data\":{\"NSE_EQ|CASH\":"
                        + "[{\"time_stamp\":1750000000000,\"buy_amount\":1.0,\"sell_amount\":1.0}]}}")));

    UpstoxContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("NEW:fii_cash.new_block");
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
