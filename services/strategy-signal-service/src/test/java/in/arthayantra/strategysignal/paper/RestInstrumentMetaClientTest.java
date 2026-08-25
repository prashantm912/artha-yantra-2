package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The lot-size asymmetry this client owes the fill-sizing paths.
 *
 * <p>A missing {@code tickSize} is a benign ROUNDING default ({@code 0.05}); a missing
 * {@code lotSize} on a DERIVATIVE is a wrong QUANTITY, so it reports {@code 0} — "unknown" — and
 * the entry paths refuse. On an EQUITY {@code 1} is the instrument's real lot and stays.
 *
 * <p>The case is reachable because {@code marketdata.instruments} holds 182,491 placeholder rows
 * ({@code tools/historical-import}) whose {@code instrument_type} is POPULATED while
 * {@code lot_size} is NULL — so the row classifies as a genuine CE/PE and answers 200.
 */
class RestInstrumentMetaClientTest {

  private MockRestServiceServer server;
  private RestInstrumentMetaClient client;

  private void wire(String responseBody) {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new RestInstrumentMetaClient(builder, "http://market-data:8081");
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/instruments/")))
        .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
  }

  @Test
  void anOptionWhoseMasterRowHasNoLotSizeReportsLotZeroNotOne() {
    // Exactly the placeholder shape: instrumentType present, lotSize + tickSize null.
    wire("{\"instrumentType\":\"CE\",\"lotSize\":null,\"tickSize\":null}");

    InstrumentMeta meta = client.meta("NFO", "NIFTY26MAY24000CE");

    assertThat(meta.instrumentClass()).isEqualTo(InstrumentClass.OPTION);
    assertThat(meta.lotSize())
        .as("a fabricated lot of 1 is a wrong QUANTITY on an option — report 0 (unknown) instead")
        .isZero();
    // the BENIGN default is untouched: a missing tick still rounds at 0.05.
    assertThat(meta.tickSize()).isEqualByComparingTo("0.05");
    server.verify();
  }

  @Test
  void futureWhoseMasterRowHasNoLotSizeAlsoReportsLotZero() {
    wire("{\"instrumentType\":\"FUT\",\"lotSize\":null,\"tickSize\":\"0.05\"}");

    InstrumentMeta meta = client.meta("NFO", "NIFTY26MAYFUT");

    assertThat(meta.instrumentClass()).isEqualTo(InstrumentClass.FUTURE);
    assertThat(meta.lotSize()).isZero();
    server.verify();
  }

  @Test
  void anEquityWhoseMasterRowHasNoLotSizeStillDefaultsToOne() {
    // The asymmetry, asserted in the direction a too-eager fix would break: 1 IS a cash equity's
    // real lot, not a substitute for an unknown one. 510 EQ rows carry a NULL lot today and two of
    // them back OPEN paper positions (NSE:KANORICHEM) — refusing those would break a live book.
    wire("{\"instrumentType\":\"EQ\",\"lotSize\":null,\"tickSize\":null}");

    InstrumentMeta meta = client.meta("NSE", "KANORICHEM");

    assertThat(meta.instrumentClass()).isEqualTo(InstrumentClass.EQUITY);
    assertThat(meta.lotSize()).isEqualTo(1L);
    server.verify();
  }

  @Test
  void anOptionWithKnownLotSizeIsReportedUnchanged() {
    // The other direction a too-eager fix would break: metadata IS present, so nothing may refuse.
    wire("{\"instrumentType\":\"CE\",\"lotSize\":75,\"tickSize\":\"0.05\"}");

    InstrumentMeta meta = client.meta("NFO", "NIFTY26AUG24000CE");

    assertThat(meta.instrumentClass()).isEqualTo(InstrumentClass.OPTION);
    assertThat(meta.lotSize()).isEqualTo(75L);
    assertThat(meta.tickSize()).isEqualByComparingTo("0.05");
    server.verify();
  }

  @Test
  void nonPositiveLotSizeOnDerivativeIsTreatedAsUnknownToo() {
    wire("{\"instrumentType\":\"PE\",\"lotSize\":0,\"tickSize\":\"0.05\"}");

    assertThat(client.meta("NFO", "NIFTY26MAY24000PE").lotSize()).isZero();
    server.verify();
  }

  private void wireFailure() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new RestInstrumentMetaClient(builder, "http://market-data:8081");
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/instruments/")))
        .andRespond(withServerError());
  }

  @Test
  void transportFailureOnACashSegmentStillDegradesToTheEquityProxy() {
    // UNCHANGED, and pinned so the Critical-1 fix is not read as widening to every symbol: blocking
    // an equity fill because market-data blipped is not a trade worth making, and lot 1 is right.
    wireFailure();

    InstrumentMeta meta = client.meta("NSE", "KANORICHEM");

    assertThat(meta.instrumentClass()).isEqualTo(InstrumentClass.EQUITY);
    assertThat(meta.lotSize()).isEqualTo(1L);
    server.verify();
  }

  @Test
  void transportFailureOnADerivativeSegmentReportsLotZero() {
    // Cross-vendor review Critical 1. The EXCHANGE already says this is not a lot-1 instrument, so a
    // failed lookup must not hand back a lot-1 proxy an NFO ticket can fill against.
    wireFailure();

    InstrumentMeta meta = client.meta("NFO", "NIFTY26AUG24000CE");

    assertThat(meta.lotSize())
        .as("a failed lookup on a derivative segment is an UNKNOWN lot, never a lot of 1")
        .isZero();
    server.verify();
  }

  @Test
  void aBfoTransportFailureReportsLotZeroToo() {
    wireFailure();

    assertThat(client.meta("BFO", "SENSEX26AUG76300CE").lotSize()).isZero();
    server.verify();
  }

  @Test
  void anEmptyBodyOnADerivativeSegmentReportsLotZero() {
    // The OTHER unresolved branch (dto == null), which took the same lot-1 proxy.
    wire("");

    assertThat(client.meta("NFO", "NIFTY26AUG24000CE").lotSize()).isZero();
    server.verify();
  }

  @Test
  void anEmptyBodyOnACashSegmentStillDefaultsToOne() {
    wire("");

    assertThat(client.meta("NSE", "KANORICHEM").lotSize()).isEqualTo(1L);
    server.verify();
  }
}
