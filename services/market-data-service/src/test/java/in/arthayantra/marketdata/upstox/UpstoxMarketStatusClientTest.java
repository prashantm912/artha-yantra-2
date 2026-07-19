package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the Pre-Open Market path: {@link UpstoxMarketStatusClient} reads the per-exchange
 * session phase ({@code /v2/market/status/{exchange}}) and batch-quotes the four index keys
 * ({@code /v2/market-quote/quotes}) on the analytics Bearer token. The critical details are asserted:
 * the {@code preOpen} boolean per phase enum, the request {@code |} key vs the response {@code :} key
 * (e.g. {@code NSE_INDEX|Nifty 50} → {@code NSE_INDEX:Nifty 50}), the derived
 * {@code %change = net_change / (ltp - net_change) * 100}, and graceful degrade to {@code UNKNOWN} /
 * price-less rows.
 */
class UpstoxMarketStatusClientTest {

  private static WireMockServer wireMock;

  // Four index quotes in ONE batch response, keyed by the ':'-delimited variant of each request '|'
  // key. Nifty 50 gains (positive net_change), Nifty Bank falls; Sensex is intentionally ABSENT so its
  // row lists price-less.
  private static final String QUOTES_BODY =
      """
      {"status":"success","data":{
        "NSE_INDEX:Nifty 50":{
          "instrument_token":"NSE_INDEX|Nifty 50","last_price":23900.0,"net_change":239.0,
          "ohlc":{"open":23700.0,"high":23950.0,"low":23680.0,"close":23661.0}},
        "NSE_INDEX:Nifty Bank":{
          "instrument_token":"NSE_INDEX|Nifty Bank","last_price":52000.0,"net_change":-100.0,
          "ohlc":{"open":52100.0,"high":52150.0,"low":51950.0,"close":52100.0}},
        "NSE_INDEX:Nifty Fin Service":{
          "instrument_token":"NSE_INDEX|Nifty Fin Service","last_price":24500.0,"net_change":0.0,
          "ohlc":{"open":24500.0,"high":24550.0,"low":24450.0,"close":24500.0}}
      }}
      """;

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

  private static UpstoxMarketStatusClient client() {
    return new UpstoxMarketStatusClient(
        RestClient.builder(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"),
        new UpstoxRateLimiter());
  }

  private static void stubStatus(String exchange, String status) {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/status/" + exchange))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"exchange\":\""
                            + exchange
                            + "\",\"status\":\""
                            + status
                            + "\",\"last_updated\":1782297000000}}")));
  }

  @Test
  void mapsEachExchangePhaseAndDerivesThePreOpenBoolean() {
    stubStatus("NSE", "PRE_OPEN_START");
    stubStatus("BSE", "NORMAL_OPEN");
    stubStatus("MCX", "CLOSING_END");

    Map<String, MarketStatus> byExchange =
        client().marketStatuses().stream()
            .collect(Collectors.toMap(MarketStatus::exchange, Function.identity()));

    // Display order preserved; pre-open detected only on the PRE_OPEN_* phase.
    assertThat(byExchange.keySet()).containsExactlyInAnyOrder("NSE", "BSE", "MCX");
    assertThat(byExchange.get("NSE").status()).isEqualTo("PRE_OPEN_START");
    assertThat(byExchange.get("NSE").preOpen()).isTrue();
    assertThat(byExchange.get("NSE").asOf()).isNotNull();
    assertThat(byExchange.get("BSE").status()).isEqualTo("NORMAL_OPEN");
    assertThat(byExchange.get("BSE").preOpen()).isFalse();
    assertThat(byExchange.get("MCX").status()).isEqualTo("CLOSING_END");
    assertThat(byExchange.get("MCX").preOpen()).isFalse();
  }

  @Test
  void preOpenIsTrueForBothPreOpenPhasesAndFalseForEveryOther() {
    for (String phase : List.of("PRE_OPEN_START", "PRE_OPEN_END")) {
      stubStatus("NSE", phase);
      MarketStatus s = client().status("NSE");
      assertThat(s.preOpen()).as("preOpen for %s", phase).isTrue();
      wireMock.resetAll();
    }
    for (String phase :
        List.of("NORMAL_OPEN", "NORMAL_CLOSE", "CLOSING_START", "CLOSING_END")) {
      stubStatus("NSE", phase);
      MarketStatus s = client().status("NSE");
      assertThat(s.preOpen()).as("preOpen for %s", phase).isFalse();
      wireMock.resetAll();
    }
  }

  @Test
  void aFailedExchangeDegradesToUnknownNeverThrows() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/status/NSE"))
            .willReturn(aResponse().withStatus(500).withBody("upstream error")));

    MarketStatus s = client().status("NSE");

    assertThat(s.exchange()).isEqualTo("NSE");
    assertThat(s.status()).isEqualTo("UNKNOWN");
    assertThat(s.preOpen()).isFalse();
    assertThat(s.asOf()).isNull();
  }

  @Test
  void preOpenJoinsNsePhaseWithTheFourIndexQuotesMappingThePipeToColonKey() {
    stubStatus("NSE", "PRE_OPEN_END");
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(QUOTES_BODY)));

    UpstoxMarketStatusClient.PreOpen snapshot = client().preOpen();

    // The page-level phase is the NSE status; PRE_OPEN_END is a pre-open phase.
    assertThat(snapshot.phase()).isEqualTo("PRE_OPEN_END");
    assertThat(snapshot.preOpen()).isTrue();

    // Always the four index rows, in display order.
    assertThat(snapshot.indices())
        .extracting(PreOpenIndex::name)
        .containsExactly("Nifty 50", "Nifty Bank", "Nifty Fin Service", "Sensex");

    // Nifty 50: quote resolved via the ':'-keyed response; %change = 239 / (23900-239) * 100 = 1.01.
    PreOpenIndex nifty = snapshot.indices().get(0);
    assertThat(nifty.key()).isEqualTo("NSE_INDEX|Nifty 50");
    assertThat(nifty.ltp()).isEqualByComparingTo("23900.0");
    assertThat(nifty.netChange()).isEqualByComparingTo("239.0");
    assertThat(nifty.prevClose()).isEqualByComparingTo("23661.0"); // ltp - net_change
    assertThat(nifty.changePct()).isEqualByComparingTo("1.01");
    assertThat(nifty.asOf()).isNotNull();

    // Nifty Bank: a negative move → negative %change = -100 / (52000+100) * 100 = -0.19.
    PreOpenIndex bank = snapshot.indices().get(1);
    assertThat(bank.netChange()).isEqualByComparingTo("-100.0");
    assertThat(bank.changePct()).isEqualByComparingTo("-0.19");

    // Nifty Fin Service: a flat move → %change exactly 0.00.
    PreOpenIndex fin = snapshot.indices().get(2);
    assertThat(fin.changePct()).isEqualByComparingTo("0.00");

    // Sensex: in the request but NOT in the quotes response → price-less row (still lists by name).
    PreOpenIndex sensex = snapshot.indices().get(3);
    assertThat(sensex.key()).isEqualTo("BSE_INDEX|SENSEX");
    assertThat(sensex.ltp()).isNull();
    assertThat(sensex.changePct()).isNull();

    // ONE batch quote call carrying the comma-joined keys + the analytics Bearer token.
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market-quote/quotes"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam(
                "instrument_key",
                equalTo(
                    "NSE_INDEX|Nifty 50,NSE_INDEX|Nifty Bank,"
                        + "NSE_INDEX|Nifty Fin Service,BSE_INDEX|SENSEX")));
  }

  @Test
  void preOpenWithAFailedQuoteBatchStillListsThePriceLessIndices() {
    stubStatus("NSE", "NORMAL_CLOSE");
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(aResponse().withStatus(500).withBody("quote error")));

    UpstoxMarketStatusClient.PreOpen snapshot = client().preOpen();

    assertThat(snapshot.phase()).isEqualTo("NORMAL_CLOSE");
    assertThat(snapshot.preOpen()).isFalse();
    assertThat(snapshot.indices())
        .extracting(PreOpenIndex::name)
        .containsExactly("Nifty 50", "Nifty Bank", "Nifty Fin Service", "Sensex");
    assertThat(snapshot.indices()).allSatisfy(i -> assertThat(i.ltp()).isNull());
  }

  @Test
  void aMissingStatusDataBlockDegradesToUnknown() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/status/MCX"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\"}")));

    MarketStatus s = client().status("MCX");

    assertThat(s.status()).isEqualTo("UNKNOWN");
    assertThat(s.preOpen()).isFalse();
  }
}
