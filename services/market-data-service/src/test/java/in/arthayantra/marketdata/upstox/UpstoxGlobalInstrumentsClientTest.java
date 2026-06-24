package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the World Indices path: {@link UpstoxGlobalInstrumentsClient} downloads + gunzips
 * the public global instrument master, keeps the {@code GLOBAL_INDEX} / {@code GLOBAL_INDICATOR} rows,
 * batch-quotes them on the analytics Bearer token, and maps each to a {@link WorldIndex}. The two
 * critical join details are asserted: the request {@code |} key vs the response {@code :} key (e.g.
 * {@code GLOBAL_INDEX|^HSI} → {@code GLOBAL_INDEX:^HSI}), and the derived
 * {@code %change = net_change / (ltp - net_change) * 100}.
 */
class UpstoxGlobalInstrumentsClientTest {

  private static WireMockServer wireMock;

  private static final String MASTER_PATH = "/market-quote/instruments/exchange/global.json.gz";

  // Two GLOBAL_INDEX rows + one GLOBAL_INDICATOR + a non-global row that must be filtered out.
  private static final String MASTER_JSON =
      """
      [
        {"segment":"GLOBAL_INDEX","name":"HANG SENG","country":"Hong Kong",
         "instrument_key":"GLOBAL_INDEX|^HSI","trading_symbol":"^HSI","exchange":"GLOBAL",
         "latency":"900 Seconds","weekly":false},
        {"segment":"GLOBAL_INDEX","name":"SGX NIFTY","country":"India",
         "instrument_key":"GLOBAL_INDEX|SGX NIFTY","trading_symbol":"SGX NIFTY","exchange":"GLOBAL",
         "latency":"900 Seconds","weekly":false},
        {"segment":"GLOBAL_INDICATOR","name":"BRENT CRUDE","country":"United Kingdom",
         "instrument_key":"GLOBAL_INDICATOR|BZUSD","trading_symbol":"BZUSD","exchange":"GLOBAL",
         "latency":"900 Seconds","weekly":false},
        {"segment":"NSE_INDEX","name":"Nifty 50","country":"India",
         "instrument_key":"NSE_INDEX|Nifty 50","trading_symbol":"Nifty 50","exchange":"NSE"}
      ]
      """;

  // The data map is keyed by the ':'-delimited variant of each request '|' key. HANG SENG carries a
  // net_change so %change derives; SGX NIFTY is quoted; BRENT is intentionally absent (price-less row).
  private static final String QUOTES_BODY =
      """
      {"status":"success","data":{
        "GLOBAL_INDEX:^HSI":{
          "instrument_token":"GLOBAL_INDEX|^HSI","last_price":23900.0,"net_change":239.0,
          "ohlc":{"open":23700.0,"high":23950.0,"low":23680.0,"close":23661.0}},
        "GLOBAL_INDEX:SGX NIFTY":{
          "instrument_token":"GLOBAL_INDEX|SGX NIFTY","last_price":23845.85,"net_change":-50.0,
          "ohlc":{"open":23900.0,"high":23920.0,"low":23800.0,"close":23895.85}}
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

  private static UpstoxGlobalInstrumentsClient client() {
    // The master host (instrumentsBaseUrl) and the API host (baseUrl) both point at WireMock here.
    return new UpstoxGlobalInstrumentsClient(
        RestClient.builder(),
        new ObjectMapper(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token", wireMock.baseUrl()));
  }

  @Test
  void joinsMasterAndQuotesMappingThePipeToColonKeyAndDerivingChangePct() {
    stubMaster(MASTER_JSON);
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(QUOTES_BODY)));

    List<WorldIndex> indices = client().worldIndices();

    // Non-global NSE_INDEX AND GLOBAL_INDICATOR (BZUSD — not quotable) are filtered; the two
    // GLOBAL_INDEX rows remain, master order preserved.
    assertThat(indices).extracting(WorldIndex::tradingSymbol).containsExactly("^HSI", "SGX NIFTY");

    // HANG SENG: quote resolved via the ':'-keyed response; %change = 239 / (23900-239) * 100 = 1.01.
    WorldIndex hsi = indices.get(0);
    assertThat(hsi.name()).isEqualTo("HANG SENG");
    assertThat(hsi.country()).isEqualTo("Hong Kong");
    assertThat(hsi.key()).isEqualTo("GLOBAL_INDEX|^HSI");
    assertThat(hsi.ltp()).isEqualByComparingTo("23900.0");
    assertThat(hsi.netChange()).isEqualByComparingTo("239.0");
    assertThat(hsi.prevClose()).isEqualByComparingTo("23661.0"); // ltp - net_change
    assertThat(hsi.changePct()).isEqualByComparingTo("1.01");
    assertThat(hsi.open()).isEqualByComparingTo("23700.0");
    assertThat(hsi.high()).isEqualByComparingTo("23950.0");
    assertThat(hsi.low()).isEqualByComparingTo("23680.0");
    assertThat(hsi.latency()).isEqualTo("900 Seconds");
    assertThat(hsi.asOf()).isNotNull();

    // SGX NIFTY: a negative move → negative %change = -50 / (23845.85+50) * 100 = -0.21.
    WorldIndex sgx = indices.get(1);
    assertThat(sgx.netChange()).isEqualByComparingTo("-50.0");
    assertThat(sgx.changePct()).isEqualByComparingTo("-0.21");

    // ONE batch quote call carrying ONLY the GLOBAL_INDEX keys (indicators excluded) + the Bearer token.
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market-quote/quotes"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam(
                "instrument_key", equalTo("GLOBAL_INDEX|^HSI,GLOBAL_INDEX|SGX NIFTY")));
  }

  @Test
  void cachesTheMasterAcrossCalls() {
    stubMaster(MASTER_JSON);
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(QUOTES_BODY)));

    UpstoxGlobalInstrumentsClient client = client();
    client.worldIndices();
    client.worldIndices();

    // The master is fetched ONCE (cached); the quote endpoint is hit per call (live).
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(MASTER_PATH)))).hasSize(1);
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo("/v2/market-quote/quotes")))).hasSize(2);
  }

  @Test
  void aFailedMasterDegradesToAnEmptyListNeverThrows() {
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH)).willReturn(aResponse().withStatus(500).withBody("upstream error")));

    assertThat(client().worldIndices()).isEmpty();
  }

  @Test
  void aFailedQuoteBatchStillListsThePriceLessUniverse() {
    stubMaster(MASTER_JSON);
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market-quote/quotes"))
            .willReturn(aResponse().withStatus(500).withBody("quote error")));

    List<WorldIndex> indices = client().worldIndices();

    assertThat(indices).extracting(WorldIndex::tradingSymbol).containsExactly("^HSI", "SGX NIFTY");
    assertThat(indices).allSatisfy(i -> assertThat(i.ltp()).isNull());
  }

  @Test
  void fetchMasterReturnsOnlyGlobalSegments() {
    stubMaster(MASTER_JSON);

    Map<String, UpstoxGlobalInstrument> byKey =
        client().fetchMaster().stream()
            .collect(java.util.stream.Collectors.toMap(UpstoxGlobalInstrument::instrumentKey, i -> i));

    assertThat(byKey).containsOnlyKeys("GLOBAL_INDEX|^HSI", "GLOBAL_INDEX|SGX NIFTY");
  }

  private static void stubMaster(String json) {
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(aResponse().withHeader("Content-Type", "application/gzip").withBody(gzip(json))));
  }

  private static byte[] gzip(String json) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }
}
