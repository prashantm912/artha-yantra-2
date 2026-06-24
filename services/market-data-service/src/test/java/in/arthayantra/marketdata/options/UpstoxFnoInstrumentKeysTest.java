package in.arthayantra.marketdata.options;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.constituents.StockUpstoxKeyMap;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsProperties;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Proves the Wave-U4 F&amp;O coverage that the SHARED U2/U3 key map ({@link UpstoxQuoteInstrumentKeys})
 * now provides: a FUT/option {@link InstrumentKey} that previously fell through to {@code null} (left
 * to the Kite path) now RESOLVES to its Upstox {@code NSE_FO|<token>} / {@code BSE_FO|<token>} key.
 * The resolver re-reads the leg's structured fields from the instruments table ({@link
 * InstrumentRepository#findByKey}, mocked) and joins the (WireMock-stubbed) Upstox master on
 * {@code (exchange, underlying, type, expiry, strike)} — never the broker symbol string.
 */
class UpstoxFnoInstrumentKeysTest {

  private static WireMockServer wireMock;
  private static final String MASTER_PATH = "/market-quote/instruments/exchange/complete.json.gz";

  private static final String MASTER_JSON =
      """
      [
        {"segment":"NSE_FO","asset_symbol":"NIFTY","instrument_key":"NSE_FO|61093",
         "instrument_type":"FUT","expiry":1785263399000,"strike_price":0.0},
        {"segment":"NSE_FO","asset_symbol":"NIFTY","instrument_key":"NSE_FO|50973",
         "instrument_type":"CE","expiry":1782844199000,"strike_price":27000.0}
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
            .willReturn(aResponse().withBody(gzip(MASTER_JSON))));
  }

  private static UpstoxFnoMasterClient master() {
    return new UpstoxFnoMasterClient(
        RestClient.builder(),
        new ObjectMapper(),
        new UpstoxAnalyticsProperties(null, null, null, wireMock.baseUrl()));
  }

  private static UpstoxQuoteInstrumentKeys keyMap(InstrumentRepository repo) {
    return new UpstoxQuoteInstrumentKeys(
        new StockUpstoxKeyMap(new ObjectMapper()),
        new UpstoxFnoInstrumentKeys(repo, master()));
  }

  @Test
  void futureKeyNowResolvesViaTheStructuredLeg() {
    InstrumentRepository repo = mock(InstrumentRepository.class);
    InstrumentKey fut = new InstrumentKey("NFO", "NIFTY26JULFUT");
    when(repo.findByKey("NFO", "NIFTY26JULFUT"))
        .thenReturn(
            Optional.of(
                instrument("NFO", "NIFTY26JULFUT", "NIFTY", "FUT", LocalDate.of(2026, 7, 28), null)));

    assertThat(keyMap(repo).key(fut)).isEqualTo("NSE_FO|61093");
  }

  @Test
  void optionKeyNowResolvesViaTheStructuredLeg() {
    InstrumentRepository repo = mock(InstrumentRepository.class);
    InstrumentKey opt = new InstrumentKey("NFO", "NIFTY2663027000CE");
    when(repo.findByKey("NFO", "NIFTY2663027000CE"))
        .thenReturn(
            Optional.of(
                instrument(
                    "NFO",
                    "NIFTY2663027000CE",
                    "NIFTY",
                    "CE",
                    LocalDate.of(2026, 6, 30),
                    new BigDecimal("27000"))));

    assertThat(keyMap(repo).key(opt)).isEqualTo("NSE_FO|50973");
  }

  @Test
  void withoutTheFnoResolverFutStillFallsThroughToNull() {
    // The pre-U4 shape (no resolver) — proves the change is purely additive.
    UpstoxQuoteInstrumentKeys preU4 =
        new UpstoxQuoteInstrumentKeys(new StockUpstoxKeyMap(new ObjectMapper()));
    assertThat(preU4.key(new InstrumentKey("NFO", "NIFTY26JULFUT"))).isNull();
  }

  @Test
  void indexAndEquityKeysAreUnchangedByTheFnoResolver() {
    InstrumentRepository repo = mock(InstrumentRepository.class);
    UpstoxQuoteInstrumentKeys map = keyMap(repo);

    // Index by display name + NSE equity via the stock reference still resolve exactly as before, and
    // the F&O resolver is never consulted for them (no findByKey).
    assertThat(map.key(new InstrumentKey("NSE", "NIFTY 50"))).isEqualTo("NSE_INDEX|Nifty 50");
    assertThat(map.key(new InstrumentKey("NSE", "RELIANCE"))).isEqualTo("NSE_EQ|INE002A01018");
  }

  private static Instrument instrument(
      String exchange,
      String tradingsymbol,
      String name,
      String type,
      LocalDate expiry,
      BigDecimal strike) {
    return new Instrument(
        exchange, tradingsymbol, 1L, name, "NFO-OPT", type, "NSE", name, expiry, strike, null, 75, true);
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
