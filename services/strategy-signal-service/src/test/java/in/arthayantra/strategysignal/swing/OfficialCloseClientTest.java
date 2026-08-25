package in.arthayantra.strategysignal.swing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link OfficialCloseClient} — the swing settle's read of the OFFICIAL NSE close (ledger H9).
 *
 * <p>Two things are proven here that nothing else can prove: that a symbol containing a RESERVED URI
 * character survives the round trip intact, and that every upstream failure degrades to an empty map
 * rather than throwing onto an exit path that may never refuse.
 */
class OfficialCloseClientTest {

  private static final LocalDate SESSION = LocalDate.of(2026, 8, 13);

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
  void aSymbolContainingAnAmpersandSurvivesTheRoundTrip() {
    // J&KBANK is a LIVE swing holding, and `&` is a RESERVED query character. Baked into a URI
    // TEMPLATE it is left alone by EncodingMode.TEMPLATE_AND_VALUES and splits the symbol list into
    // a second query parameter — the client would then silently ask for "J" and drop "KBANK". Passed
    // as a URI VARIABLE it is strictly encoded. This asserts what the SERVER received, which is the
    // only vantage point that can tell the two apart.
    stubOk(
        """
        {"items":[
          {"tradingsymbol":"J&KBANK","tradeDate":"2026-08-13","closePrice":"111.2500",
           "lastPrice":"111.0000","series":"EQ"}]}
        """);

    Map<String, BigDecimal> closes =
        client().closesOn("NSE", SESSION, List.of("J&KBANK", "PRECOT"));

    LoggedRequest request =
        wireMock.findAll(getRequestedFor(urlPathEqualTo("/api/v1/market/eod-close"))).get(0);
    assertThat(request.queryParameter("symbols").firstValue())
        .as("the whole comma-joined list arrives as ONE parameter value, ampersand intact")
        .isEqualTo("J&KBANK,PRECOT");
    assertThat(request.queryParameter("exchange").firstValue()).isEqualTo("NSE");
    assertThat(request.queryParameter("date").firstValue()).isEqualTo("2026-08-13");
    assertThat(closes).containsEntry("J&KBANK", new BigDecimal("111.2500"));
  }

  @Test
  void decimalStringsParseStraightIntoBigDecimalWithoutADoubleHop() {
    stubOk(
        """
        {"items":[
          {"tradingsymbol":"PRECOT","tradeDate":"2026-08-13","closePrice":"0.1000000000000000055511151231257827",
           "lastPrice":null,"series":"EQ"}]}
        """);

    Map<String, BigDecimal> closes = client().closesOn("NSE", SESSION, List.of("PRECOT"));

    assertThat(closes.get("PRECOT"))
        .as("a double hop would round this to 0.1 — money never goes through a double")
        .isEqualByComparingTo(new BigDecimal("0.1000000000000000055511151231257827"));
  }

  @Test
  void aRowStampedWithADifferentSessionIsDropped() {
    // A price is only meaningful with its session attached — that is the whole of H9. A wrong-session
    // close is indistinguishable from a right one once it reaches the fill, so it is refused here.
    stubOk(
        """
        {"items":[
          {"tradingsymbol":"PRECOT","tradeDate":"2026-08-12","closePrice":"99.0000",
           "lastPrice":"99.0000","series":"EQ"},
          {"tradingsymbol":"SATIN","tradeDate":"2026-08-13","closePrice":"88.0000",
           "lastPrice":"88.0000","series":"EQ"}]}
        """);

    Map<String, BigDecimal> closes =
        client().closesOn("NSE", SESSION, List.of("PRECOT", "SATIN"));

    assertThat(closes).containsOnlyKeys("SATIN");
  }

  @Test
  void aNullClosePriceIsDroppedRatherThanMappedToNull() {
    stubOk(
        """
        {"items":[
          {"tradingsymbol":"PRECOT","tradeDate":"2026-08-13","closePrice":null,
           "lastPrice":null,"series":"EQ"}]}
        """);

    assertThat(client().closesOn("NSE", SESSION, List.of("PRECOT"))).isEmpty();
  }

  @Test
  void anUpstreamErrorDegradesToAnEmptyMapRatherThanThrowing() {
    // The caller is an exit settle. "Entries need fresh truth (you can always NOT enter), exits need
    // the best available truth (you cannot refuse to leave forever)" — so a 500 must return empty and
    // let every symbol take the counted, alerted candle-close fallback, never propagate.
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/eod-close"))
            .willReturn(aResponse().withStatus(500).withBody("boom")));

    assertThat(client().closesOn("NSE", SESSION, List.of("PRECOT"))).isEmpty();
  }

  @Test
  void malformedJsonDegradesToAnEmptyMapRatherThanThrowing() {
    stubOk("{\"items\": [ this is not json");

    assertThat(client().closesOn("NSE", SESSION, List.of("PRECOT"))).isEmpty();
  }

  @Test
  void aSlowMarketDataFailsFastRatherThanParkingTheSettle() {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/eod-close"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(3_000).withBody("{\"items\":[]}")));
    OfficialCloseClient client =
        new OfficialCloseClient(RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 250);

    long startedAt = System.nanoTime();
    Map<String, BigDecimal> closes = client.closesOn("NSE", SESSION, List.of("PRECOT"));
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(closes).isEmpty();
    assertThat(elapsedMs).as("the bounded read timeout fired well inside the 3s stall").isLessThan(2_500);
  }

  @Test
  void anEmptySymbolListNeverReachesTheNetwork() {
    stubOk("{\"items\":[]}");

    assertThat(client().closesOn("NSE", SESSION, List.of())).isEmpty();
    assertThat(client().closesOn("NSE", SESSION, List.of("", " "))).isEmpty();
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/api/v1/market/eod-close"))))
        .as("a flat book is an ordinary night — no request, no cost")
        .isEmpty();
  }

  private OfficialCloseClient client() {
    return new OfficialCloseClient(
        RestClient.builder(), new ObjectMapper(), wireMock.baseUrl(), 5_000);
  }

  private void stubOk(String body) {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/v1/market/eod-close"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }
}
