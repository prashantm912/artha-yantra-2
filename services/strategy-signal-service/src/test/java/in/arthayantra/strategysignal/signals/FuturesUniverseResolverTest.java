package in.arthayantra.strategysignal.signals;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.client.RestClient;

/** E1 §3.3: screener-pick parsing and live universe resolution semantics. */
class FuturesUniverseResolverTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @BeforeEach
  void resetWireMock() {
    wireMock.resetAll();
  }

  @Test
  void restFailureReturnsAnUnresolvedUniverse() {
    stub(
        "/api/v1/market/futures/term-structure",
        503,
        "{\"error\":\"kite unavailable\"}");

    assertThat(resolver().resolve("NSE", "NIFTY 50", "front_month", 2))
        .isEqualTo(Optional.empty());
  }

  @Test
  void missingFuturesContractsOnAnIndexRootIsUnresolvedNotEmpty() {
    // THE 2026-07-15/16 OUTAGE, pinned. A 404 NOT_FOUND_INSTRUMENT means "no active non-expired FUT
    // row for this underlying". For an INDEX root — every live strategy signals on NIFTY 50 /
    // SENSEX — that can never legitimately mean "nothing to trade": the index always has listed
    // futures. So a 404 here is a DATA FAULT (tombstoned NFO sync, drifted underlying derivation,
    // typo'd ref) and MUST surface as UNRESOLVED. Reading it as a genuine empty is precisely the
    // outage: 0 strategies loaded, 0 drops counted, and the retry chain reporting "resolved every
    // universe" while the session emits nothing.
    stub(
        "/api/v1/market/futures/term-structure",
        404,
        "{\"code\":\"NOT_FOUND_INSTRUMENT\",\"message\":\"no FUT contracts\",\"details\":{}}");

    assertThat(resolver().resolve("NSE", "NIFTY 50", "front_month", 2))
        .isEqualTo(Optional.empty());
  }

  @Test
  void screenerSkipsACashOnlyMoverButStillResolvesTheRest() {
    // The screener is the ONE path where a 404 is legitimate: a picked STOCK mover may simply have
    // no listed futures. It must be skipped, not treated as an upstream fault — otherwise one
    // cash-only pick bricks the whole screen forever and burns the retry chain every login.
    stub(
        "/api/v1/market/futures/movers-screen",
        200,
        "{\"longCandidates\":[{\"symbol\":\"CASHONLY\"},{\"symbol\":\"HDFCBANK\"}]}");
    stubTermStructure(
        "CASHONLY",
        404,
        "{\"code\":\"NOT_FOUND_INSTRUMENT\",\"message\":\"no FUT contracts\",\"details\":{}}");
    stubTermStructure(
        "HDFCBANK",
        200,
        "{\"contracts\":[{\"exchange\":\"NFO\",\"tradingsymbol\":\"HDFCBANK26AUGFUT\","
            + "\"expiry\":\"2026-08-27\"}]}");

    assertThat(resolver().resolveScreener("long", 5, "captured"))
        .hasValueSatisfying(
            refs ->
                assertThat(refs)
                    .singleElement()
                    .extracting(StrategyDefinition.InstrumentRef::tradingsymbol)
                    .isEqualTo("HDFCBANK26AUGFUT"));
  }

  @Test
  void nonArrayContractsBodyReturnsAnUnresolvedUniverse() {
    stub(
        "/api/v1/market/futures/term-structure",
        200,
        "{\"contracts\":{\"symbol\":\"garbled\"}}");

    assertThat(resolver().resolve("NSE", "NIFTY 50", "front_month", 2))
        .isEqualTo(Optional.empty());
  }

  @Test
  void screenerPropagatesInnerResolutionFailureInsteadOfReportingAnEmptyScreen() {
    stub(
        "/api/v1/market/futures/movers-screen",
        200,
        "{\"longCandidates\":[{\"symbol\":\"HDFCBANK\"},{\"symbol\":\"ICICIBANK\"}]}");
    stubTermStructure(
        "HDFCBANK",
        200,
        "{\"contracts\":[{\"tradingsymbol\":\"HDFCBANK26JULFUT\",\"expiry\":\"2026-07-30\"}]}");
    stubTermStructure(
        "ICICIBANK",
        503,
        "{\"error\":\"kite unavailable\"}");

    assertThat(resolver().resolveScreener("long", 5, "captured"))
        .isEqualTo(Optional.empty());
  }

  @Test
  void screenerPicksRanksBySideAndCapsAtMaxPicks() throws Exception {
    var screen =
        mapper.readTree(
            """
            {"longCandidates":[{"symbol":"HDFCBANK"},{"symbol":"ICICIBANK"},{"symbol":"AXISBANK"}],
             "shortCandidates":[{"symbol":"PNB"}]}
            """);
    assertThat(FuturesUniverseResolver.screenerPicks(screen, "long", 2))
        .containsExactly("HDFCBANK", "ICICIBANK"); // top-2 in conviction order
    assertThat(FuturesUniverseResolver.screenerPicks(screen, "short", 5))
        .containsExactly("PNB");
  }

  @Test
  void screenerPicksDedupesAndSkipsBlanksAndMissingLists() throws Exception {
    var screen =
        mapper.readTree(
            """
            {"longCandidates":[{"symbol":"SBIN"},{"symbol":""},{"symbol":"SBIN"},{"symbol":"CANBK"}]}
            """);
    assertThat(FuturesUniverseResolver.screenerPicks(screen, "long", 9))
        .containsExactly("SBIN", "CANBK"); // blank skipped, duplicate collapsed
    assertThat(FuturesUniverseResolver.screenerPicks(mapper.readTree("{}"), "long", 5)).isEmpty();
  }

  private FuturesUniverseResolver resolver() {
    return new FuturesUniverseResolver(
        RestClient.builder(),
        mapper,
        Clock.fixed(Instant.parse("2026-07-16T03:45:00Z"), ZoneOffset.UTC),
        wireMock.baseUrl());
  }

  private static void stub(String path, int status, String body) {
    wireMock.stubFor(
        get(urlPathEqualTo(path))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private static void stubTermStructure(String underlying, int status, String body) {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/futures/term-structure"))
            .withQueryParam("underlying", equalTo(underlying))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }
}
