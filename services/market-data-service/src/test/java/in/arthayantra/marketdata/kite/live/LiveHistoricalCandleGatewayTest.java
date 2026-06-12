package in.arthayantra.marketdata.kite.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.InstrumentTokenResolver;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Phase-11 WireMock test: live historical fetch over the Kite wire format (B-6 fidelity). */
class LiveHistoricalCandleGatewayTest {

  private static final String BODY =
      """
      {"status":"success","data":{"candles":[
        ["2026-02-03T09:15:00+0530",100.50,101.00,100.00,100.75,1500,12345],
        ["2026-02-03T09:16:00+0530",100.75,101.25,100.50,101.00,900,12400]
      ]}}
      """;

  private static WireMockServer wireMock;

  @BeforeAll
  static void start() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @BeforeEach
  void reset() {
    wireMock.resetAll();
  }

  private static KiteCallExecutor generousExecutor() {
    return new KiteCallExecutor(
        RateLimiterRegistry.of(
            RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build()),
        CircuitBreakerRegistry.of(CircuitBreakerConfig.custom().minimumNumberOfCalls(1_000).build()),
        new SimpleMeterRegistry());
  }

  private static LiveHistoricalCandleGateway gateway(String instrumentType, AccessTokenProvider tokens) {
    InstrumentTokenResolver resolver =
        key ->
            Optional.of(
                new InstrumentTokenResolver.TokenInfo(
                    408_065, instrumentType, "FUT".equals(instrumentType) ? "NFO-FUT" : "NSE"));
    return new LiveHistoricalCandleGateway(
        RestClient.builder(),
        wireMock.baseUrl(),
        "test-key",
        tokens,
        resolver,
        generousExecutor(),
        new ObjectMapper());
  }

  private static final Instant FROM = OffsetDateTime.parse("2026-02-03T09:15:00+05:30").toInstant();
  private static final Instant TO = OffsetDateTime.parse("2026-02-03T09:17:00+05:30").toInstant();

  @Test
  void fetchesAndParsesKiteCandlesWithAuthHeadersAndIstOffsets() {
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/minute"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(BODY)));

    List<HistoricalCandleGateway.Candle> candles =
        gateway("EQ", () -> Optional.of("test-token"))
            .fetch(new InstrumentKey("NSE", "RELIANCE"), "1m", FROM, TO);

    assertThat(candles).hasSize(2);
    assertThat(candles.get(0).bucketStart())
        .isEqualTo(OffsetDateTime.parse("2026-02-03T09:15:00+05:30"));
    assertThat(candles.get(0).open()).isEqualByComparingTo("100.50");
    assertThat(candles.get(0).volume()).isEqualTo(1500);
    assertThat(candles.get(0).oi()).isEqualTo(12345);

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/instruments/historical/408065/minute"))
            .withHeader("X-Kite-Version", equalTo("3"))
            .withHeader("Authorization", equalTo("token test-key:test-token"))
            .withQueryParam("oi", equalTo("1"))
            .withQueryParam("continuous", equalTo("0")));
  }

  @Test
  void derivativesFetchWithContinuousFlag() {
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/day"))
            .willReturn(aResponse().withBody("{\"status\":\"success\",\"data\":{\"candles\":[]}}")));

    gateway("FUT", () -> Optional.of("test-token"))
        .fetch(new InstrumentKey("NFO", "NIFTY26JUNFUT"), "1d", FROM, TO);

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/instruments/historical/408065/day"))
            .withQueryParam("continuous", equalTo("1"))
            .withQueryParam("oi", equalTo("1")));
  }

  @Test
  void kite429WithRetryAfterIsBackedOffThenSucceeds() {
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/minute"))
            .inScenario("backoff")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
            .willSetStateTo("recovered"));
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/minute"))
            .inScenario("backoff")
            .whenScenarioStateIs("recovered")
            .willReturn(aResponse().withBody(BODY)));

    long start = System.nanoTime();
    List<HistoricalCandleGateway.Candle> candles =
        gateway("EQ", () -> Optional.of("test-token"))
            .fetch(new InstrumentKey("NSE", "RELIANCE"), "1m", FROM, TO);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(candles).hasSize(2);
    assertThat(elapsedMs).as("Retry-After: 1 must be waited out").isGreaterThanOrEqualTo(950);
    assertThat(wireMock.getAllServeEvents()).hasSize(2);
  }

  @Test
  void missingSessionFailsWith401TokenExpired() {
    assertThatThrownBy(
            () ->
                gateway("EQ", Optional::empty)
                    .fetch(new InstrumentKey("NSE", "RELIANCE"), "1m", FROM, TO))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.httpStatus()).isEqualTo(401);
              assertThat(e.code()).isEqualTo(ErrorCodes.KITE_TOKEN_EXPIRED);
            });
  }
}
