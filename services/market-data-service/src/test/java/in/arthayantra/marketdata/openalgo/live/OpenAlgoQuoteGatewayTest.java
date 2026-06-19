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
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import in.arthayantra.marketdata.kite.QuoteGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link OpenAlgoQuoteGateway}: a NIFTY quote round-trips through OpenAlgo's
 * single-symbol {@code POST /api/v1/quotes} into the domain {@link QuoteGateway.Quote}, and the
 * index underlying is remapped to OpenAlgo's {@code NSE_INDEX}/{@code NIFTY} pair (plan §3 / M0).
 */
class OpenAlgoQuoteGatewayTest {

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

  private static OpenAlgoQuoteGateway gateway() {
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
    return new OpenAlgoQuoteGateway(
        RestClient.builder(), wireMock.baseUrl(), "test-key", executor, new ObjectMapper());
  }

  @Test
  void parsesQuoteAndRemapsIndexUnderlying() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/quotes"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"ltp\":24137.55,\"bid\":24137.5,"
                            + "\"ask\":24138.0,\"volume\":987654,\"oi\":12345}}")));
    InstrumentKey nifty = new InstrumentKey("NSE", "NIFTY 50");

    Map<InstrumentKey, QuoteGateway.Quote> quotes = gateway().quotes(List.of(nifty));

    assertThat(quotes).hasSize(1);
    assertThat(quotes.get(nifty).lastPrice()).isEqualByComparingTo("24137.55");
    assertThat(quotes.get(nifty).bid()).isEqualByComparingTo("24137.5");
    assertThat(quotes.get(nifty).volume()).isEqualTo(987654L);
    assertThat(quotes.get(nifty).oi()).isEqualTo(12345L);

    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/quotes"))
            .withRequestBody(matchingJsonPath("$.apikey", equalTo("test-key")))
            .withRequestBody(matchingJsonPath("$.symbol", equalTo("NIFTY")))
            .withRequestBody(matchingJsonPath("$.exchange", equalTo("NSE_INDEX"))));
  }

  @Test
  void fansOutOnePostPerKey() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/quotes"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\",\"data\":{\"ltp\":1.0}}")));

    gateway()
        .quotes(
            List.of(
                new InstrumentKey("NFO", "SYM1"),
                new InstrumentKey("NFO", "SYM2"),
                new InstrumentKey("NFO", "SYM3")));

    assertThat(wireMock.getAllServeEvents())
        .as("single-symbol endpoint -> one POST per key")
        .hasSize(3);
  }
}
