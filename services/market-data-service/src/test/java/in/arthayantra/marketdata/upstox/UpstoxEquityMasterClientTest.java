package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract test for {@link UpstoxEquityMasterClient}: it fetches the SAME gzipped Upstox
 * instrument master from the assets CDN as the F&amp;O client, keeps only the {@code NSE_EQ} rows, and
 * resolves a cash-equity tradingsymbol to its {@code NSE_EQ|<ISIN>} {@code instrument_key}. The fixture
 * carries two NSE_EQ rows + a non-equity NSE_FO row + an NSE_INDEX row; only the equities resolve.
 */
class UpstoxEquityMasterClientTest {

  private static WireMockServer wireMock;

  private static final String MASTER_PATH = "/market-quote/instruments/exchange/complete.json.gz";

  private static final String MASTER_JSON =
      """
      [
        {"segment":"NSE_EQ","name":"Reliance Industries Ltd","asset_symbol":"RELIANCE",
         "instrument_key":"NSE_EQ|INE002A01018","instrument_type":"EQ","trading_symbol":"RELIANCE",
         "expiry":null,"strike_price":0.0},
        {"segment":"NSE_EQ","name":"Tata Consultancy Services Ltd","asset_symbol":"TCS",
         "instrument_key":"NSE_EQ|INE467B01029","instrument_type":"EQ","trading_symbol":"TCS",
         "expiry":null,"strike_price":0.0},
        {"segment":"NSE_FO","name":"NIFTY","asset_symbol":"NIFTY","underlying_symbol":"NIFTY",
         "instrument_key":"NSE_FO|61093","instrument_type":"FUT","trading_symbol":"NIFTY FUT 28 JUL 26",
         "expiry":1785263399000,"strike_price":0.0},
        {"segment":"NSE_INDEX","name":"NIFTY 50","asset_symbol":null,
         "instrument_key":"NSE_INDEX|Nifty 50","instrument_type":"INDEX","trading_symbol":"Nifty 50",
         "expiry":null,"strike_price":0.0}
      ]
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
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/gzip")
                    .withBody(gzip(MASTER_JSON))));
  }

  private static UpstoxEquityMasterClient client() {
    return new UpstoxEquityMasterClient(
        RestClient.builder(),
        new ObjectMapper(),
        new UpstoxAnalyticsProperties(null, null, null, wireMock.baseUrl()));
  }

  @Test
  void resolvesAnEquitySymbolToItsNseEqKey() {
    UpstoxEquityMasterClient client = client();

    assertThat(client.equityKey("RELIANCE")).isEqualTo("NSE_EQ|INE002A01018");
    assertThat(client.equityKey("tcs")).as("lookup is case-insensitive").isEqualTo("NSE_EQ|INE467B01029");
    wireMock.verify(getRequestedFor(urlPathEqualTo(MASTER_PATH)));
  }

  @Test
  void nonEquityRowsAndUnknownSymbolsResolveToNull() {
    UpstoxEquityMasterClient client = client();

    // an F&O / index row is never indexed under its symbol
    assertThat(client.equityKey("NIFTY")).isNull();
    // unknown equity
    assertThat(client.equityKey("NOTLISTED")).isNull();
    // null / blank
    assertThat(client.equityKey(null)).isNull();
    assertThat(client.equityKey("  ")).isNull();
  }

  @Test
  void aFailedOrMalformedMasterFetchDegradesToNullNeverThrows() {
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH))
            .willReturn(aResponse().withStatus(500).withBody("upstream error")));
    assertThat(client().equityKey("RELIANCE")).isNull();

    wireMock.resetAll();
    wireMock.stubFor(
        get(urlPathEqualTo(MASTER_PATH)).willReturn(aResponse().withBody("not-gzip-bytes")));
    assertThat(client().equityKey("RELIANCE")).isNull();
  }

  @Test
  void cachesTheMasterAcrossLookups() {
    UpstoxEquityMasterClient client = client();

    client.equityKey("RELIANCE");
    client.equityKey("TCS");

    assertThat(wireMock.getAllServeEvents()).hasSize(1);
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
