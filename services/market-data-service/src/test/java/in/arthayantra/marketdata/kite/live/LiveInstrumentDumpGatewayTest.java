package in.arthayantra.marketdata.kite.live;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Phase-9 WireMock test: live dump download (gzip CSV) + 5xx handling (B-6 wire fidelity). */
class LiveInstrumentDumpGatewayTest {

  private static final String CSV =
      """
      instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,lot_size,tick_size,instrument_type,segment,exchange
      256265,1001,NIFTY 50,"NIFTY 50",0,,0,1,0.05,EQ,INDICES,NSE
      100001,391,RELIANCE,"RELIANCE INDUSTRIES",0,,0,1,0.05,EQ,NSE,NSE
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

  private LiveInstrumentDumpGateway gateway() {
    var executor =
        new in.arthayantra.marketdata.kite.KiteCallExecutor(
            io.github.resilience4j.ratelimiter.RateLimiterRegistry.of(
                io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
                    .limitForPeriod(100)
                    .limitRefreshPeriod(java.time.Duration.ofSeconds(1))
                    .timeoutDuration(java.time.Duration.ofSeconds(5))
                    .build()),
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.of(
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                    .minimumNumberOfCalls(1_000)
                    .build()),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    return new LiveInstrumentDumpGateway(
        RestClient.builder(), wireMock.baseUrl(), "test-key", () -> Optional.of("test-token"), executor);
  }

  @Test
  void downloadsAndParsesGzippedCsvWithAuthHeaders() throws IOException {
    wireMock.stubFor(
        get(urlEqualTo("/instruments/NSE"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "text/csv")
                    .withBody(gzip(CSV))));
    wireMock.stubFor(
        get(urlEqualTo("/instruments/BSE")).willReturn(aResponse().withBody("instrument_token\n")));
    wireMock.stubFor(
        get(urlEqualTo("/instruments/NFO")).willReturn(aResponse().withBody("instrument_token\n")));
    wireMock.stubFor(
        get(urlEqualTo("/instruments/BFO")).willReturn(aResponse().withBody("instrument_token\n")));

    var rows = gateway().fetchDump();

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).tradingsymbol()).isEqualTo("NIFTY 50");
    assertThat(rows.get(1).name()).isEqualTo("RELIANCE INDUSTRIES");
    assertThat(rows.get(1).tickSize()).isEqualByComparingTo("0.05");
    wireMock.verify(
        getRequestedFor(urlEqualTo("/instruments/NSE"))
            .withHeader("X-Kite-Version", equalTo("3"))
            .withHeader("Authorization", equalTo("token test-key:test-token")));
  }

  @Test
  void serverErrorsPropagate() {
    wireMock.stubFor(get(urlEqualTo("/instruments/NSE")).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> gateway().fetchDump()).hasMessageContaining("503");
  }

  @Test
  void missingTokenShortCircuitsWithTokenExpired() {
    var gateway =
        new LiveInstrumentDumpGateway(
            RestClient.builder(),
            wireMock.baseUrl(),
            "test-key",
            Optional::empty,
            new in.arthayantra.marketdata.kite.KiteCallExecutor(
                io.github.resilience4j.ratelimiter.RateLimiterRegistry.ofDefaults(),
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.of(
                    io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .minimumNumberOfCalls(1_000)
                        .build()),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    assertThatThrownBy(gateway::fetchDump).hasMessageContaining("no live Kite session");
  }

  @Test
  void parseCsvCapturesAllTwelveColumns() {
    String csv =
        "instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,lot_size,"
            + "tick_size,instrument_type,segment,exchange\n"
            + "256265,1001,NIFTY 50,\"NIFTY 50\",24100.5,,0,1,0.05,EQ,INDICES,NSE\n";

    var rows = LiveInstrumentDumpGateway.parseCsv(csv.getBytes(StandardCharsets.UTF_8));

    assertThat(rows).hasSize(1);
    var k = rows.get(0);
    assertThat(k.instrumentToken()).isEqualTo(256265L);
    assertThat(k.exchangeToken()).isEqualTo(1001L);
    assertThat(k.lastPrice()).isEqualByComparingTo("24100.5");
    assertThat(k.tradingsymbol()).isEqualTo("NIFTY 50");
    assertThat(k.tickSize()).isEqualByComparingTo("0.05");
    assertThat(k.lotSize()).isEqualTo(1);
    assertThat(k.exchange()).isEqualTo("NSE");
  }

  private static byte[] gzip(String text) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
      gz.write(text.getBytes(StandardCharsets.UTF_8));
    }
    return bytes.toByteArray();
  }

  /**
   * ⚠️ A tokenless dump must not reach the executor at all.
   *
   * <p>Until 2026-08-28 the token was resolved INSIDE the supplier handed to
   * {@code KiteCallExecutor}, so a boot with no session still acquired the limiter permit and
   * recorded the local {@code KITE_TOKEN_EXPIRED} as a {@code kite-rest} failure — while making
   * zero wire calls. The DUMP bucket refills ONE permit per ~30 minutes for the whole
   * three-exchange sweep, so that is a real cost: the 08:30 sync and its catch-ups could spend the
   * budget on a no-op.
   *
   * <p>Found by cross-vendor review. I had claimed a cleared token cost "zero wire calls, zero
   * breaker pressure, zero rate budget" — true for the quote and historical gateways, which
   * resolve first, and FALSE here. Generalising from two of three.
   */
  @Test
  void aDumpWithNoSessionNeverEntersTheExecutor() {
    in.arthayantra.marketdata.kite.KiteCallExecutor executor =
        org.mockito.Mockito.mock(in.arthayantra.marketdata.kite.KiteCallExecutor.class);
    LiveInstrumentDumpGateway gateway =
        new LiveInstrumentDumpGateway(
            org.springframework.web.client.RestClient.builder(),
            "https://api.kite.trade",
            "placeholder-api-key",
            java.util.Optional::empty,
            executor);

    org.assertj.core.api.Assertions.assertThatThrownBy(gateway::fetchDump)
        .isInstanceOf(in.arthayantra.common.web.error.ApiException.class)
        .hasMessageContaining("no live Kite session for dump sync");

    org.mockito.Mockito.verifyNoInteractions(executor);
  }
}
