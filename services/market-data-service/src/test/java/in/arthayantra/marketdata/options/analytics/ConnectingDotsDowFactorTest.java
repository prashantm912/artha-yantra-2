package in.arthayantra.marketdata.options.analytics;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsProperties;
import in.arthayantra.marketdata.upstox.UpstoxGlobalInstrumentsClient;
import in.arthayantra.marketdata.upstox.UpstoxGlobalQuoteClient;
import in.arthayantra.marketdata.upstox.UpstoxRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

/**
 * End-to-end proof for the Dow dot: an Upstox world-indices {@code ^DJI} tick reaches {@code
 * ConnectingDotsService.dowFactor} as a NON-Neutral factor code. Before the Upstox-backed {@link
 * GlobalQuoteSource} existed the only implementation was the OpenAlgo one, which needs an
 * Upstox-backed appliance and therefore never resolved — every row read Neutral.
 *
 * <p>The default (no bean bound, i.e. both flags off) still reads Neutral, byte-identical to the
 * shipped behaviour, and a broken feed degrades to Neutral rather than propagating.
 */
class ConnectingDotsDowFactorTest {

  /** 2026-08-01 13:22 IST — the session under test IS "today" for this fixed clock. */
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-01T07:52:11Z"), ZoneOffset.UTC);

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 1);
  private static final String QUOTES_PATH = "/v2/market-quote/quotes";

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

  @Test
  void aLiveDjiTickAboveItsPrevCloseMakesTheDowDotBullish() {
    stubDji("52548.91", "319.85"); // ltp above prev close (52229.06)

    assertThat(service(upstoxSource()).dowFactor(TODAY)).isEqualTo(ConnectingDotsService.BULLISH);
  }

  @Test
  void aLiveDjiTickBelowItsPrevCloseMakesTheDowDotBearish() {
    stubDji("52000.00", "-229.06"); // ltp below prev close (52229.06)

    assertThat(service(upstoxSource()).dowFactor(TODAY)).isEqualTo(ConnectingDotsService.BEARISH);
  }

  @Test
  void noGlobalQuoteBeanLeavesTheDowDotNeutralAsShipped() {
    stubDji("52548.91", "319.85"); // a live feed exists, but no bean is wired to read it

    assertThat(service(null).dowFactor(TODAY)).isEqualTo(ConnectingDotsService.NEUTRAL);
  }

  @Test
  void aBrokenGlobalFeedDegradesToNeutralRatherThanFailing() {
    wireMock.stubFor(
        get(urlPathEqualTo(QUOTES_PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));

    assertThat(service(upstoxSource()).dowFactor(TODAY)).isEqualTo(ConnectingDotsService.NEUTRAL);
  }

  @Test
  void aPastSessionStaysNeutralEvenWithALiveFeed() {
    stubDji("52548.91", "319.85");

    assertThat(service(upstoxSource()).dowFactor(TODAY.minusDays(1)))
        .isEqualTo(ConnectingDotsService.NEUTRAL);
  }

  /** The scorer with every collaborator the Dow path does NOT touch left null. */
  @SuppressWarnings("unchecked")
  private static ConnectingDotsService service(GlobalQuoteSource source) {
    ObjectProvider<GlobalQuoteSource> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(source);
    return new ConnectingDotsService(null, null, null, null, null, null, provider, CLOCK);
  }

  private static GlobalQuoteSource upstoxSource() {
    return new UpstoxGlobalQuoteClient(
        new UpstoxGlobalInstrumentsClient(
            RestClient.builder(),
            new ObjectMapper(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token", wireMock.baseUrl()),
            new UpstoxRateLimiter()),
        new SimpleMeterRegistry());
  }

  private static void stubDji(String lastPrice, String netChange) {
    wireMock.stubFor(
        get(urlPathEqualTo(QUOTES_PATH))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"GLOBAL_INDEX:^DJI\":{"
                            + "\"last_price\":" + lastPrice + ",\"net_change\":" + netChange + ","
                            + "\"ohlc\":{\"open\":52256.03,\"high\":52566.85,\"low\":52017.32,"
                            + "\"close\":52229.06}}}}")));
  }
}
