package in.arthayantra.marketdata.openalgo.canary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
 * OpenAlgo canary IT (live profile vs WireMock, ntfy captured on the same server), twin of
 * {@code ContractCanaryIntegrationTest}: faithful responses -> ZERO drift; a removed per-strike
 * {@code chain[].ce.oi} -> critical drift + ntfy POST (the OI-capture guard, plan §17.11); an added
 * top-level block -> warning; the Redis daily-once marker makes the second run of a day a no-op.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=live",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.ntfy.topic=ay-test-topic",
      "artha.openalgo.contract-canary-enabled=true"
    })
class OpenAlgoContractCanaryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
  private static final Path SECRETS_DIR;

  static {
    WIREMOCK.start();
    try {
      SECRETS_DIR = Files.createTempDirectory("openalgo-canary-secrets");
      Files.writeString(SECRETS_DIR.resolve("kite_api_key"), "test-key");
      Files.writeString(SECRETS_DIR.resolve("kite_api_secret"), "test-secret");
      Files.writeString(SECRETS_DIR.resolve("openalgo_api_key"), "test-openalgo-key");
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
  }

  private static final String QUOTE_BODY =
      "{\"status\":\"success\",\"data\":{\"ltp\":24137.55,\"oi\":0}}";
  private static final String HISTORY_BODY =
      "{\"status\":\"success\",\"data\":[{\"timestamp\":1718434500,\"open\":1,\"high\":1,\"low\":1,"
          + "\"close\":1,\"volume\":1,\"oi\":0}]}";

  @Autowired private OpenAlgoContractCanary canary;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void stubFaithfulFixtures() {
    WIREMOCK.resetAll();
    redis.delete(OpenAlgoContractCanary.MARKER_KEY_PREFIX + LocalDate.now(java.time.ZoneOffset.UTC));
    redis.delete(
        OpenAlgoContractCanary.MARKER_KEY_PREFIX
            + LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    WIREMOCK.stubFor(post(urlPathEqualTo("/api/v1/quotes")).willReturn(json(QUOTE_BODY)));
    WIREMOCK.stubFor(post(urlPathEqualTo("/api/v1/history")).willReturn(json(HISTORY_BODY)));
    WIREMOCK.stubFor(post(urlPathEqualTo("/ay-test-topic")).willReturn(aResponse().withStatus(200)));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  @Test
  void fixtureFaithfulResponsesProduceZeroDrift() {
    OpenAlgoContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).isEmpty();
    assertThat(result.lastContractCheck()).isNotNull();
    WIREMOCK.verify(0, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
    assertThat(redis.opsForValue().get(OpenAlgoContractCanary.RESULT_KEY)).contains("\"drift\":[]");
  }

  @Test
  void removedConsumedFieldIsCriticalDriftWithNtfyAlert() {
    // quote loses ltp — a removed CONSUMED field must surface as critical drift (the per-strike
    // optionchain OI probe is Phase 1, §17.11; this proves the MISSING path on a Phase-0 probe)
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/api/v1/quotes"))
            .willReturn(json("{\"status\":\"success\",\"data\":{\"oi\":0}}")));

    OpenAlgoContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("MISSING:quote.data.ltp");
    WIREMOCK.verify(1, postRequestedFor(urlPathEqualTo("/ay-test-topic")));
  }

  @Test
  void addedTopLevelFieldIsWarningNotCritical() {
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/api/v1/quotes"))
            .willReturn(
                json("{\"status\":\"success\",\"new_block\":{\"x\":1},\"data\":{\"ltp\":1,\"oi\":0}}")));

    OpenAlgoContractCanary.CanaryResult result = canary.runNow();

    assertThat(result.drift()).contains("NEW:quote.new_block");
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
