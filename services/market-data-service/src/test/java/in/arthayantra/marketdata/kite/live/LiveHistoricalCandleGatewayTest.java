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

  /**
   * ⚠️ Pins the REMOTE half of the accident that keeps {@code BhavcopyCloseCanary}'s comparison
   * population intact.
   *
   * <p>{@code candles} has {@code source} outside its primary key and {@code upsertAuthoritativeAll}
   * keeps the EXISTING source when the incoming bar matches on every field INCLUDING {@code oi}. The
   * bhavcopy projection writes {@code oi = null}; this gateway asks for {@code oi=1} and a cash
   * equity answers with a literal {@code 0}. {@code NULL IS NOT DISTINCT FROM 0} is FALSE, so a Kite
   * bar written over an OHLCV-identical bhavcopy bar takes the Kite source and stays in the canary's
   * population. {@code
   * CandleCaggIntegrationTest#kiteOverBhavcopyTakesTheKiteSourceBecauseOnlyOneSideEncodesNoOiAsZero}
   * pins the WRITE half — but it hand-seeds the Kite side as {@code 0L}, so it assumes exactly what
   * this test measures: that a wire {@code 0} survives the whole decode path as {@code 0} rather
   * than being normalised to null somewhere between {@link
   * in.arthayantra.marketdata.kite.wire.KiteCandle} and the port record.
   *
   * <p><b>⚠️ The residual gap, stated because a guard that reads wider than it is would be worse
   * than none.</b> This pins OUR mapping against a canned response; it cannot pin KITE's choice to
   * SEND the seventh element. If Kite ever omitted it for cash equities the second half of this test
   * shows what happens — {@code oi} decodes to null, {@code NULL IS NOT DISTINCT FROM NULL} is TRUE,
   * and a value-identical Kite bar would silently KEEP {@code source='BHAVCOPY'}, so the canary
   * would lose precisely the bars that agree perfectly and surface it as nothing louder than a
   * slightly short {@code compared} count. The daily {@code ContractCanary} does not close that: its
   * {@code historical} manifest entry stops at {@code data.candles: array} (no per-candle arity, no
   * {@code oi}), its probe is the NIFTY 50 INDEX on the {@code minute} interval and sends no {@code
   * oi=1} at all, and its path resolver has no array-index syntax — so pinning the wire arity there
   * needs a new equity/day/{@code oi=1} probe plus resolver support, which is its own change.
   */
  @Test
  void cashEquityDailyBarKeepsAZeroOpenInterestRatherThanNulling() {
    // A cash equity's 1d page as Kite returns it under oi=1: seven positions, the last a literal 0.
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/day"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"success","data":{"candles":[
                          ["2026-02-03T00:00:00+0530",100.00,101.00,99.00,100.50,500,0]
                        ]}}
                        """)));

    List<HistoricalCandleGateway.Candle> candles =
        gateway("EQ", () -> Optional.of("test-token"))
            .fetch(new InstrumentKey("NSE", "RELIANCE"), "1d", FROM, TO);

    assertThat(candles).hasSize(1);
    assertThat(candles.get(0).oi())
        .as(
            "a wire 0 must reach candles.oi as 0, not null — null is what the bhavcopy projection"
                + " writes, and matching it would strand OHLCV-identical Kite bars at"
                + " source='BHAVCOPY', outside BhavcopyCloseCanary's comparison population")
        .isEqualTo(0L);
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/instruments/historical/408065/day"))
            .withQueryParam("oi", equalTo("1")));

    // The counterfactual, so the guarantee's CONDITION is visible next to the guarantee: an omitted
    // seventh element decodes to null and the provenance flip stops happening. Nothing in this repo
    // can stop Kite doing that; this is the shape to look for if `compared` ever drifts down.
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/day"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"success","data":{"candles":[
                          ["2026-02-03T00:00:00+0530",100.00,101.00,99.00,100.50,500]
                        ]}}
                        """)));

    assertThat(
            gateway("EQ", () -> Optional.of("test-token"))
                .fetch(new InstrumentKey("NSE", "RELIANCE"), "1d", FROM, TO)
                .get(0)
                .oi())
        .as("an ABSENT seventh element is the hazard shape — it decodes to null, not 0")
        .isNull();
  }

  @Test
  void optionsFetchWithContinuousButFutNeverDoes() {
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/day"))
            .willReturn(aResponse().withBody("{\"status\":\"success\",\"data\":{\"candles\":[]}}")));

    // FUT: per-contract history only — Kite's roll-unaware continuous series is FORBIDDEN
    // for futures (B-18/B-19); the CONT stitch is built locally in Phase 15B
    gateway("FUT", () -> Optional.of("test-token"))
        .fetch(new InstrumentKey("NFO", "NIFTY26JUNFUT"), "1d", FROM, TO);
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/instruments/historical/408065/day"))
            .withQueryParam("continuous", equalTo("0"))
            .withQueryParam("oi", equalTo("1")));

    gateway("CE", () -> Optional.of("test-token"))
        .fetch(new InstrumentKey("NFO", "NIFTY2661624000CE"), "1d", FROM, TO);
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/instruments/historical/408065/day"))
            .withQueryParam("continuous", equalTo("1")));
  }

  @Test
  void optionMinuteFetchSendsContinuousZero() {
    // Kite 400s "invalid interval for continuous data" when continuous=1 is sent with the minute
    // interval, so option 1m candles (straddle/options-premium charts) MUST send continuous=0.
    // Regression for the blanked option-premium charts (live-verified 2026-06-23).
    wireMock.stubFor(
        get(urlPathEqualTo("/instruments/historical/408065/minute"))
            .willReturn(aResponse().withBody("{\"status\":\"success\",\"data\":{\"candles\":[]}}")));

    gateway("CE", () -> Optional.of("test-token"))
        .fetch(new InstrumentKey("NFO", "NIFTY2661624000CE"), "1m", FROM, TO);

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/instruments/historical/408065/minute"))
            .withQueryParam("continuous", equalTo("0"))
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
