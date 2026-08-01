package in.arthayantra.strategysignal.signals;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.strategyengine.series.EngineCandle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test (real HTTP, so the explicit request-factory timeout applies). Locks two contracts:
 * (1) the candle warm-up query encoding — an IST {@code +05:30} timestamp reaches the wire as a UTC
 * {@code …Z} instant, never a literal {@code +} (which the receiver decodes as a space and 500s,
 * leaving the engine permanently cold); (2) a slow/failing market-data fetch is bounded by the total
 * timeout and returns empty rather than parking the single-threaded eval loop (2026-07-14 incident).
 */
class MarketDataCandlesClientTest {

  private WireMockServer wireMock;

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  @Test
  void sendsUtcInstantsSoThePlusOffsetIsNeverDecodedAsASpace() {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/candles"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));
    MarketDataCandlesClient client =
        new MarketDataCandlesClient(
            RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 10_000);

    client.fetch(
        "NSE",
        "RELIANCE",
        "1m",
        OffsetDateTime.parse("2026-06-09T09:11:17.871210208+05:30"),
        OffsetDateTime.parse("2026-06-09T10:11:17+05:30"));

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/market/candles"))
            .withQueryParam("from", equalTo("2026-06-09T03:41:17.871210208Z"))
            .withQueryParam("to", equalTo("2026-06-09T04:41:17Z")));
  }

  @Test
  void staleResponseIsSilentlyDroppedByDefault() {
    // M14 red-proof, part 1: BEFORE the fix, a stale:true envelope produced no observable signal
    // at all from this client — the flag was parsed by nobody. A response with no `stale` key at
    // all (every pre-M14 stub in this file) must keep behaving exactly the same way.
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/candles"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[]}")));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    MarketDataCandlesClient client =
        new MarketDataCandlesClient(
            RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 10_000, meters);

    client.fetch(
        "NSE", "RELIANCE", "1d",
        OffsetDateTime.parse("2026-06-09T09:11:17+05:30"),
        OffsetDateTime.parse("2026-06-09T10:11:17+05:30"));

    assertThat(meters.counter("ay_candle_fetch_stale_total").count()).isZero();
  }

  @Test
  void staleResponseIsCountedAndTheCandlesStillReturn() {
    // M14 red-proof, part 2: a stale:true envelope now increments the visibility counter — but
    // the candles it carries are STILL returned unchanged (visibility only, never a refusal
    // gate — this must never turn into an entries/exits behaviour change).
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/candles"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"bucket\":\"2026-06-09T09:15:00+05:30\",\"open\":\"1\","
                            + "\"high\":\"1\",\"low\":\"1\",\"close\":\"1\",\"volume\":1,"
                            + "\"oi\":null}],\"stale\":true,"
                            + "\"asOf\":\"2026-06-08T15:30:00+05:30\"}")));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    MarketDataCandlesClient client =
        new MarketDataCandlesClient(
            RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 10_000, meters);

    List<EngineCandle> result =
        client.fetch(
            "NSE", "RELIANCE", "1d",
            OffsetDateTime.parse("2026-06-09T09:11:17+05:30"),
            OffsetDateTime.parse("2026-06-09T10:11:17+05:30"));

    assertThat(result).hasSize(1);
    assertThat(meters.counter("ay_candle_fetch_stale_total").count()).isEqualTo(1.0);
  }

  @Test
  void returnsEmptyAndDoesNotThrowWhenTheFetchExceedsTheTimeout() {
    // A NON-empty response held 3s past a 300ms client timeout: `isEmpty()` proves the empty came
    // from the total-timeout catch (not the body), and the elapsed ceiling proves the fetch did NOT
    // wait the full delay — so a slow-but-alive market-data can't park the single-threaded eval loop.
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/candles"))
            .willReturn(
                aResponse()
                    .withFixedDelay(3_000)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"bucket\":\"2026-06-09T09:15:00+05:30\",\"open\":\"1\","
                            + "\"high\":\"1\",\"low\":\"1\",\"close\":\"1\",\"volume\":1,\"oi\":null}]}")));
    MarketDataCandlesClient slowClient =
        new MarketDataCandlesClient(
            RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 300);

    long startedAt = System.nanoTime();
    List<EngineCandle> result =
        slowClient.fetch(
            "NSE",
            "RELIANCE",
            "1m",
            OffsetDateTime.parse("2026-06-09T09:11:17+05:30"),
            OffsetDateTime.parse("2026-06-09T10:11:17+05:30"));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(result).isEmpty();
    assertThat(elapsedMs).isLessThan(2_000);
  }
}
