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
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.openalgo.live.OpenAlgoGlobalQuoteClient;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsProperties;
import in.arthayantra.marketdata.upstox.UpstoxGlobalInstrumentsClient;
import in.arthayantra.marketdata.upstox.UpstoxGlobalQuoteClient;
import in.arthayantra.marketdata.upstox.UpstoxRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
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
  private static final InstrumentKey DOW = new InstrumentKey("GLOBAL_INDEX", "DOWJONES");

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

  /**
   * The fabricated-input guard, end to end. {@code OpenAlgoMappers.toQuote} maps a MISSING {@code ltp}
   * to {@code BigDecimal.ZERO}, so an appliance that does not serve DOWJONES yields "price 0" against
   * a real prev close. Unguarded, {@code dowFactor} computes {@code 0 − 52229.06 < 0} and scores a
   * violently BEARISH Dow on every row — strictly worse than a dark dot, because it is invented data
   * feeding a live scoring input. It must land on NEUTRAL.
   */
  @Test
  void aZeroLtpOpenAlgoQuoteDoesNotManufactureABearishDot() {
    QuoteGateway zeroLtp =
        keys ->
            Map.of(
                DOW,
                new QuoteGateway.Quote(
                    DOW,
                    BigDecimal.ZERO, // exactly what OpenAlgoMappers produces for a missing ltp
                    null,
                    null,
                    null,
                    null,
                    new QuoteGateway.Quote.Ohlc(null, null, null, new BigDecimal("52229.06")),
                    OffsetDateTime.now(ZoneOffset.UTC)));

    ConnectingDotsService svc =
        service(new OpenAlgoGlobalQuoteClient(zeroLtp, new SimpleMeterRegistry()));

    assertThat(svc.dowFactor(TODAY))
        .as("a zero LTP must read as NO DATA, never as a bearish Dow")
        .isEqualTo(ConnectingDotsService.NEUTRAL);
  }

  /**
   * An LTP with no prev close lands on Neutral — but Neutral alone proves nothing here, since the
   * unguarded path reaches Neutral too. The load-bearing assertion is that it is COUNTED: that is
   * the difference between a dark dot and a genuine one.
   */
  @Test
  void aQuoteWithNoPrevCloseLandsOnNeutralAndIsCounted() {
    stubQuotes("""
        {"status":"success","data":{"GLOBAL_INDEX:^DJI":{"last_price":52548.91}}}
        """);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    assertThat(service(upstoxSource(meters)).dowFactor(TODAY))
        .isEqualTo(ConnectingDotsService.NEUTRAL);
    assertThat(
            meters
                .counter(
                    GlobalQuoteSource.DEGRADED_METRIC, "source", "upstox", "reason", "no-prev-close")
                .count())
        .as("an unusable quote must be visible, not silently Neutral")
        .isEqualTo(1.0);
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
    return upstoxSource(new SimpleMeterRegistry());
  }

  private static GlobalQuoteSource upstoxSource(SimpleMeterRegistry meters) {
    return new UpstoxGlobalQuoteClient(
        new UpstoxGlobalInstrumentsClient(
            RestClient.builder(),
            new ObjectMapper(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token", wireMock.baseUrl()),
            new UpstoxRateLimiter()),
        meters);
  }

  private static void stubDji(String lastPrice, String netChange) {
    stubQuotes(
        "{\"status\":\"success\",\"data\":{\"GLOBAL_INDEX:^DJI\":{"
            + "\"last_price\":" + lastPrice + ",\"net_change\":" + netChange + ","
            + "\"ohlc\":{\"open\":52256.03,\"high\":52566.85,\"low\":52017.32,"
            + "\"close\":52229.06}}}}");
  }

  private static void stubQuotes(String body) {
    wireMock.stubFor(
        get(urlPathEqualTo(QUOTES_PATH))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }
}
