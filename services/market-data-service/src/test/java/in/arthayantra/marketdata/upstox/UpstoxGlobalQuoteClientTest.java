package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.kite.GlobalQuoteSource;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway.Quote;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * The Upstox-backed {@link GlobalQuoteSource}: the canonical {@code GLOBAL_INDEX@DOWJONES} key is
 * translated to Upstox's {@code GLOBAL_INDEX|^DJI}, the response tick maps to a domain quote with LTP
 * plus the prev close in the OHLC close slot (the two fields {@code ConnectingDotsService.dowFactor}
 * reads), and EVERY degradation path is observable — WARN + {@link GlobalQuoteSource#DEGRADED_METRIC}
 * — instead of the silent {@code Optional.empty()} that hid a permanently-Neutral Dow dot for months.
 *
 * <p>The stubbed body mirrors the LIVE {@code GET /api/v1/market/world-indices} row probed
 * 2026-08-01 (ltp 52548.91, net_change 319.85 ⇒ prev close 52229.06).
 */
class UpstoxGlobalQuoteClientTest {

  private static final InstrumentKey DOW = new InstrumentKey("GLOBAL_INDEX", "DOWJONES");
  private static final String QUOTES_PATH = "/v2/market-quote/quotes";

  private static WireMockServer wireMock;

  private SimpleMeterRegistry meters;
  private ListAppender<ILoggingEvent> logs;
  private ch.qos.logback.classic.Logger clientLog;

  // Upstox keys the data map with the ':'-delimited variant of the requested '|' key.
  private static final String DJI_BODY =
      """
      {"status":"success","data":{
        "GLOBAL_INDEX:^DJI":{
          "instrument_token":"GLOBAL_INDEX|^DJI","last_price":52548.91,"net_change":319.85,
          "ohlc":{"open":52256.03,"high":52566.85,"low":52017.32,"close":52229.06}}
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
  void setUp() {
    wireMock.resetAll();
    meters = new SimpleMeterRegistry();
    clientLog = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UpstoxGlobalQuoteClient.class);
    logs = new ListAppender<>();
    logs.start();
    clientLog.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    clientLog.detachAppender(logs);
  }

  private UpstoxGlobalQuoteClient client() {
    UpstoxGlobalInstrumentsClient globals =
        new UpstoxGlobalInstrumentsClient(
            RestClient.builder(),
            new ObjectMapper(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token", wireMock.baseUrl()),
            new UpstoxRateLimiter());
    return new UpstoxGlobalQuoteClient(globals, meters);
  }

  private double degradations(String reason) {
    return meters
        .counter(GlobalQuoteSource.DEGRADED_METRIC, "source", "upstox", "reason", reason)
        .count();
  }

  @Test
  void dowResolvesToTheDjiKeyAndCarriesLtpPlusPrevClose() {
    stubQuotes(DJI_BODY);

    Optional<Quote> quote = client().latest(DOW);

    assertThat(quote).isPresent();
    Quote q = quote.orElseThrow();
    assertThat(q.key()).isEqualTo(DOW); // the CANONICAL key is never rewritten
    assertThat(q.lastPrice()).isEqualByComparingTo("52548.91");
    // prev close rides the OHLC close slot — what dowFactor / GET /global/dow read.
    assertThat(q.ohlc()).isNotNull();
    assertThat(q.ohlc().close()).isEqualByComparingTo("52229.06"); // ltp - net_change
    assertThat(q.ohlc().open()).isEqualByComparingTo("52256.03");
    assertThat(q.ohlc().high()).isEqualByComparingTo("52566.85");
    assertThat(q.ohlc().low()).isEqualByComparingTo("52017.32");
    assertThat(q.timestamp()).isNotNull();

    // The edge mapper sent Upstox's key, not ours.
    wireMock.verify(
        getRequestedFor(urlPathEqualTo(QUOTES_PATH))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("instrument_key", equalTo("GLOBAL_INDEX|^DJI")));

    // A good read degrades nothing.
    assertThat(meters.find(GlobalQuoteSource.DEGRADED_METRIC).counters()).isEmpty();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void prevCloseFallsBackToTheOhlcCloseWhenNetChangeIsAbsent() {
    stubQuotes(
        """
        {"status":"success","data":{
          "GLOBAL_INDEX:^DJI":{"last_price":52548.91,
            "ohlc":{"open":52256.03,"high":52566.85,"low":52017.32,"close":52229.06}}
        }}
        """);

    Quote q = client().latest(DOW).orElseThrow();

    assertThat(q.ohlc().close()).isEqualByComparingTo("52229.06");
  }

  @Test
  void anUnmappedGlobalKeyDegradesLoudlyWithoutCallingUpstox() {
    Optional<Quote> quote = client().latest(new InstrumentKey("GLOBAL_INDEX", "NASDAQ"));

    assertThat(quote).isEmpty();
    assertThat(degradations("unmapped")).isEqualTo(1.0);
    assertWarned("reason=unmapped");
    assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(QUOTES_PATH)))).isEmpty();
  }

  @Test
  void anUpstreamFailureDegradesLoudlyInsteadOfSilentlyNeutral() {
    wireMock.stubFor(
        get(urlPathEqualTo(QUOTES_PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));

    assertThat(client().latest(DOW)).isEmpty();
    assertThat(degradations("error")).isEqualTo(1.0);
    assertWarned("reason=error");
  }

  @Test
  void anAbsentQuoteDegradesLoudlyInsteadOfSilentlyNeutral() {
    stubQuotes("""
        {"status":"success","data":{}}
        """);

    assertThat(client().latest(DOW)).isEmpty();
    assertThat(degradations("absent")).isEqualTo(1.0);
    assertWarned("reason=absent");
  }

  @Test
  void aPricelessTickCountsAsAbsentRatherThanAQuoteWithNoLtp() {
    stubQuotes(
        """
        {"status":"success","data":{"GLOBAL_INDEX:^DJI":{"net_change":319.85}}}
        """);

    assertThat(client().latest(DOW)).isEmpty();
    assertThat(degradations("absent")).isEqualTo(1.0);
  }

  private void assertWarned(String fragment) {
    assertThat(logs.list)
        .as("a dark Dow dot must be visible in the log, not just in the counter")
        .anySatisfy(
            e -> {
              assertThat(e.getLevel()).isEqualTo(Level.WARN);
              assertThat(e.getFormattedMessage()).contains("degraded to neutral").contains(fragment);
            });
  }

  private static void stubQuotes(String body) {
    wireMock.stubFor(
        get(urlPathEqualTo(QUOTES_PATH))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }
}
