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
import in.arthayantra.marketdata.kite.OptionChainGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link OpenAlgoOptionChainGateway}: OpenAlgo {@code POST /api/v1/optionchain}
 * CE/PE legs map into the venue-neutral domain chain with the load-bearing per-strike {@code oi},
 * the index underlying remapped ({@code NIFTY 50 -> NIFTY}/{@code NSE_INDEX}) and the expiry sent as
 * the {@code DDMMMYY} token (plan §4.5 / §17.4).
 */
class OpenAlgoOptionChainGatewayTest {

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

  private static OpenAlgoOptionChainGateway gateway() {
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
    return new OpenAlgoOptionChainGateway(
        RestClient.builder(), wireMock.baseUrl(), "test-key", executor, new ObjectMapper());
  }

  @Test
  void mapsChainRowsWithPerStrikeOi() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/optionchain"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"underlying\":\"NIFTY\","
                            + "\"underlying_ltp\":24250.50,\"expiry_date\":\"30DEC26\","
                            + "\"atm_strike\":24250.0,\"chain\":["
                            + "{\"strike\":24000.0,"
                            + "\"ce\":{\"symbol\":\"NIFTY30DEC2624000CE\",\"ltp\":320.5,\"bid\":320.0,"
                            + "\"ask\":321.0,\"volume\":1000,\"oi\":150000},"
                            + "\"pe\":{\"symbol\":\"NIFTY30DEC2624000PE\",\"ltp\":85.25,\"bid\":85.0,"
                            + "\"ask\":86.0,\"volume\":2000,\"oi\":250000}},"
                            + "{\"strike\":24500.0,"
                            + "\"ce\":{\"symbol\":\"NIFTY30DEC2624500CE\",\"ltp\":120.0,\"bid\":119.0,"
                            + "\"ask\":121.0,\"volume\":500,\"oi\":80000},"
                            + "\"pe\":{\"symbol\":\"NIFTY30DEC2624500PE\",\"ltp\":260.0,\"bid\":259.0,"
                            + "\"ask\":261.0,\"volume\":1500,\"oi\":300000}}"
                            + "]}")));

    OptionChainGateway.Chain chain =
        gateway().fetch(new InstrumentKey("NSE", "NIFTY 50"), LocalDate.of(2026, 12, 30));

    assertThat(chain.underlying()).isEqualTo("NIFTY");
    assertThat(chain.underlyingLtp()).isEqualByComparingTo("24250.50");
    assertThat(chain.rows()).hasSize(2);
    OptionChainGateway.Row first = chain.rows().get(0);
    assertThat(first.strike()).isEqualByComparingTo("24000.0");
    assertThat(first.ce().oi()).isEqualTo(150000L);
    assertThat(first.ce().ltp()).isEqualByComparingTo("320.5");
    assertThat(first.pe().oi()).isEqualTo(250000L);
    // the §17.11 OI-coverage signal: sum across both strikes' CE+PE legs
    assertThat(chain.totalOi()).isEqualTo(150000L + 250000L + 80000L + 300000L);

    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/optionchain"))
            .withRequestBody(matchingJsonPath("$.underlying", equalTo("NIFTY")))
            .withRequestBody(matchingJsonPath("$.exchange", equalTo("NSE_INDEX")))
            .withRequestBody(matchingJsonPath("$.expiry_date", equalTo("30DEC26"))));
  }

  @Test
  void emptyChainYieldsZeroRowsAndZeroOi() {
    wireMock.stubFor(
        post(urlPathEqualTo("/api/v1/optionchain"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"underlying\":\"NIFTY\","
                            + "\"underlying_ltp\":24250.50,\"expiry_date\":\"30DEC26\",\"chain\":[]}")));

    OptionChainGateway.Chain chain =
        gateway().fetch(new InstrumentKey("NSE", "NIFTY 50"), LocalDate.of(2026, 12, 30));

    assertThat(chain.rows()).isEmpty();
    assertThat(chain.totalOi()).isZero();
  }
}
