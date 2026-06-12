package in.arthayantra.marketdata.kite.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Phase-15 WireMock test: batched quotes over the Kite wire format + batch splitting (B-3). */
class LiveQuoteGatewayTest {

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

  private static LiveQuoteGateway gateway(int batchSize) {
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
    return new LiveQuoteGateway(
        RestClient.builder(),
        wireMock.baseUrl(),
        "test-key",
        () -> Optional.of("test-token"),
        executor,
        new ObjectMapper(),
        batchSize);
  }

  private static final String BODY =
      """
      {"status":"success","data":{
        "NFO:NIFTY2661624000CE":{"last_price":312.55,"volume":123456,"oi":987654,
          "depth":{"buy":[{"price":312.05}],"sell":[{"price":313.00}]}},
        "NSE:NIFTY 50":{"last_price":24137.55,
          "depth":{"buy":[{"price":0}],"sell":[{"price":0}]}}
      }}
      """;

  @Test
  void parsesQuotesWithDepthVolumeAndOi() {
    wireMock.stubFor(get(urlPathEqualTo("/quote")).willReturn(aResponse().withBody(BODY)));
    InstrumentKey option = new InstrumentKey("NFO", "NIFTY2661624000CE");
    InstrumentKey index = new InstrumentKey("NSE", "NIFTY 50");

    Map<InstrumentKey, QuoteGateway.Quote> quotes = gateway(250).quotes(List.of(option, index));

    assertThat(quotes).hasSize(2);
    assertThat(quotes.get(option).lastPrice()).isEqualByComparingTo("312.55");
    assertThat(quotes.get(option).bid()).isEqualByComparingTo("312.05");
    assertThat(quotes.get(option).ask()).isEqualByComparingTo("313.00");
    assertThat(quotes.get(option).volume()).isEqualTo(123456);
    assertThat(quotes.get(option).oi()).isEqualTo(987654);
    assertThat(quotes.get(index).lastPrice()).isEqualByComparingTo("24137.55");

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/quote"))
            .withHeader("X-Kite-Version", equalTo("3"))
            .withHeader("Authorization", equalTo("token test-key:test-token"))
            .withQueryParam("i", equalTo("NFO:NIFTY2661624000CE")));
  }

  @Test
  void splitsRequestsIntoBatches() {
    wireMock.stubFor(
        get(urlPathEqualTo("/quote"))
            .willReturn(aResponse().withBody("{\"status\":\"success\",\"data\":{}}")));
    List<InstrumentKey> keys = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      keys.add(new InstrumentKey("NFO", "SYM" + i));
    }

    gateway(3).quotes(keys);

    assertThat(wireMock.getAllServeEvents())
        .as("7 keys at batch size 3 -> 3 wire calls")
        .hasSize(3);
  }
}
