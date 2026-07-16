package in.arthayantra.marketdata.constituents;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.nse.LiveIndexConstituentsFetcher;
import in.arthayantra.marketdata.nse.NseHttpClient;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

/** Wire-level acceptance tests for the live NSE constituent CSV source. */
@ExtendWith(OutputCaptureExtension.class)
class LiveIndexConstituentsFetcherTest {

  private static final String PATH = "/content/indices/ind_nifty100list.csv";
  private static final String HEADER = "Company Name,Industry,Symbol,Series,ISIN Code";
  private static final String HAPPY_BODY =
      HEADER
          + "\nABB India Ltd.,Capital Goods,ABB,EQ,INE117A01022"
          + "\nAdani Enterprises Ltd.,Metals,ADANIENT,EQ,INE423A01024"
          + "\nAxis Bank Ltd.,Financial Services,AXISBANK,EQ,INE238A01034"
          + "\nBajaj Auto Ltd.,Automobile,BAJAJ-AUTO,EQ,INE917I01010"
          + "\nBharti Airtel Ltd.,Telecommunication,BHARTIARTL,EQ,INE397D01024"
          + "\nCipla Ltd.,Healthcare,CIPLA,EQ,INE059A01026"
          + "\nDivi's Laboratories Ltd.,Healthcare,DIVISLAB,EQ,INE361B01024"
          + "\nEicher Motors Ltd.,Automobile,EICHERMOT,EQ,INE066A01013"
          + "\nHDFC Bank Ltd.,Financial Services,HDFCBANK,EQ,INE040A01034"
          + "\nNifty BeES,ETF,NIFTYBEES,BE,INF204KB17I5";

  private static final String MALFORMED_LINE = "\"Foo, Inc.\",Power,FOO,EQ,INE1";

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
  void parsesRealShapedHeaderAndEqRows() {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(aResponse().withStatus(200).withBody(HAPPY_BODY)));

    assertThat(fetcher().fetch("NIFTY 100"))
        .contains(
            List.of(
                new IndexConstituentsFetcher.Constituent("NSE", "ABB"),
                new IndexConstituentsFetcher.Constituent("NSE", "ADANIENT"),
                new IndexConstituentsFetcher.Constituent("NSE", "AXISBANK"),
                new IndexConstituentsFetcher.Constituent("NSE", "BAJAJ-AUTO"),
                new IndexConstituentsFetcher.Constituent("NSE", "BHARTIARTL"),
                new IndexConstituentsFetcher.Constituent("NSE", "CIPLA"),
                new IndexConstituentsFetcher.Constituent("NSE", "DIVISLAB"),
                new IndexConstituentsFetcher.Constituent("NSE", "EICHERMOT"),
                new IndexConstituentsFetcher.Constituent("NSE", "HDFCBANK")));
  }

  /**
   * A field-count drift is the silent-shift hazard the quote guard also protects against: if a row
   * loses (or gains) a column, {@code cells[2]} stops being Symbol and every later index shifts, so a
   * naive parser would emit garbage or drop the row into a list that still looks fine.
   */
  @Test
  void rejectsAFieldCountDriftLoudlyAndNamesTheLine(CapturedOutput output) {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(HEADER + "\nShort Row Ltd.,Power,SHORTY,EQ")));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
    assertThat(output).contains("4 fields, expected 5");
  }

  /** A blank symbol must fail the FETCH, not create a Constituent with an empty tradingsymbol. */
  @Test
  void rejectsABlankSymbolRatherThanEmittingAnInvalidMember(CapturedOutput output) {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(
                aResponse().withStatus(200).withBody(HEADER + "\nGhost Ltd.,Power,,EQ,INE9")));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
    assertThat(output).contains("blank symbol or series");
  }

  /** A header-only body is a realistic NSE failure (published-but-empty) and must not accrue. */
  @Test
  void rejectsAHeaderOnlyBodyRatherThanAccruingAnEmptyMembership() {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(HEADER)));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
  }

  /**
   * Drives the THROW side of the &lt;90%-EQ guard. {@code HAPPY_BODY} sits exactly ON the boundary
   * (9 EQ of 10 rows = 90%, passing only because the comparison is strict {@code <}), so without this
   * the arithmetic is pinned at its edge but never proven to reject. A truncated/degraded NSE file
   * must fail the fetch rather than accrue a short membership — {@code accrue} is append-only, so a
   * short snapshot is indistinguishable from a real one forever after.
   */
  @Test
  void rejectsAnImplausiblyShortEqListRatherThanAccruingIt(CapturedOutput output) {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        HEADER
                            + "\nReal Ltd.,Power,REAL,EQ,INE1"
                            + "\nSurveilled A Ltd.,Power,SURVA,BE,INE2"
                            + "\nSurveilled B Ltd.,Power,SURVB,BE,INE3")));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
    assertThat(output).contains("implausibly short EQ list");
  }

  /** A dropped non-EQ row is LOUD — silently shrinking a membership list is the defect, not the fix. */
  @Test
  void namesEveryDroppedNonEqRow(CapturedOutput output) {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(aResponse().withStatus(200).withBody(HAPPY_BODY)));

    assertThat(fetcher().fetch("NIFTY 100")).isPresent();
    assertThat(output).contains("dropping non-EQ constituent NIFTYBEES (series BE)");
  }

  @Test
  void treatsHtmlBlockPageWithoutNseHeaderAsEmpty() {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "<html><body>Company Name access denied</body></html>\n"
                            + "Blocked company,Power,FOO,EQ,INE1")));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
    wireMock.verify(getRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void rejectsQuotedMalformedRowLoudlyAndNamesTheLine(CapturedOutput output) {
    wireMock.stubFor(
        get(urlPathEqualTo(PATH))
            .willReturn(aResponse().withStatus(200).withBody(HEADER + "\n" + MALFORMED_LINE)));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
    assertThat(output).contains("Foo, Inc.").contains("Power,FOO,EQ,INE1");
    wireMock.verify(getRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void returnsEmptyWithoutCallingNseForAnUnmappableIndexName() {
    assertThat(fetcher().fetch("UNKNOWN IDX")).isEmpty();
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  @Test
  void turnsNse404IntoEmptyOptionalWithoutThrowing() {
    wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

    assertThat(fetcher().fetch("NIFTY 100")).isEmpty();
    wireMock.verify(getRequestedFor(urlPathEqualTo(PATH)));
  }

  private static LiveIndexConstituentsFetcher fetcher() {
    return new LiveIndexConstituentsFetcher(
        new NseHttpClient(RestClient.builder(), wireMock.baseUrl()), wireMock.baseUrl());
  }
}
