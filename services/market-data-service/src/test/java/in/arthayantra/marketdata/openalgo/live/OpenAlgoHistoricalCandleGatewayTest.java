package in.arthayantra.marketdata.openalgo.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.kite.HistoricalCandleGateway.Candle;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link OpenAlgoHistoricalCandleGateway}: OpenAlgo {@code POST /api/v1/history}
 * OHLCV+OI rows map into the domain {@link Candle}, with {@code 1d -> D} interval mapping and the
 * index underlying remapped (plan §3).
 */
class OpenAlgoHistoricalCandleGatewayTest {

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

  private static OpenAlgoHistoricalCandleGateway gateway() {
    KiteCallExecutor executor =
        new KiteCallExecutor(
            RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                    .limitForPeriod(100)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(Duration.ofSeconds(5))
                    .build()),
            CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().minimumNumberOfCalls(1_000).build()),
            new SimpleMeterRegistry());
    return new OpenAlgoHistoricalCandleGateway(
        RestClient.builder(), wireMock.baseUrl(), "test-key", executor, new ObjectMapper());
  }

  @Test
  void mapsDailyCandlesWithOiAndMapsInterval() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/history"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":[{\"timestamp\":1749753000,"
                            + "\"open\":24100.0,\"high\":24200.0,\"low\":24050.0,\"close\":24137.55,"
                            + "\"volume\":1500,\"oi\":12345}]}")));
    InstrumentKey nifty = new InstrumentKey("NSE", "NIFTY 50");

    List<Candle> candles =
        gateway()
            .fetch(
                nifty,
                "1d",
                Instant.parse("2026-06-10T00:00:00Z"),
                Instant.parse("2026-06-15T00:00:00Z"));

    assertThat(candles).hasSize(1);
    assertThat(candles.get(0).close()).isEqualByComparingTo("24137.55");
    assertThat(candles.get(0).volume()).isEqualTo(1500L);
    assertThat(candles.get(0).oi()).isEqualTo(12345L);
    assertThat(candles.get(0).interval()).isEqualTo("1d");
    assertThat(candles.get(0).bucketStart()).isNotNull();

    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/history"))
            .withRequestBody(matchingJsonPath("$.interval", equalTo("D")))
            .withRequestBody(matchingJsonPath("$.symbol", equalTo("NIFTY")))
            .withRequestBody(matchingJsonPath("$.exchange", equalTo("NSE_INDEX"))));
  }
}
