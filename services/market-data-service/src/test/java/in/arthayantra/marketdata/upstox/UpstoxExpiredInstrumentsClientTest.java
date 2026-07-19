package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Leg;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link UpstoxExpiredInstrumentsClient}: each of the four expired-instruments
 * endpoints is parsed into its domain shape, with the Bearer token and request path asserted. The
 * candle response is the positional {@code [ts,o,h,l,c,v,oi]} array — index 6 is the open interest.
 */
class UpstoxExpiredInstrumentsClientTest {

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

  private static UpstoxExpiredInstrumentsClient client() {
    return new UpstoxExpiredInstrumentsClient(
        RestClient.builder(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"),
        new UpstoxRateLimiter());
  }

  @Test
  void expiriesParsesDatesAndSendsBearer() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/expired-instruments/expiries"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\",\"data\":[\"2026-06-16\",\"2026-06-09\"]}")));

    List<LocalDate> expiries = client().expiries("NSE_INDEX|Nifty 50");

    assertThat(expiries).containsExactly(LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 9));
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/expired-instruments/expiries"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("instrument_key", equalTo("NSE_INDEX|Nifty 50")));
  }

  @Test
  void optionContractsParseSpec() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/expired-instruments/option/contract"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":[{"
                            + "\"segment\":\"NSE_FO\",\"instrument_key\":\"NSE_FO|50436|16-06-2026\","
                            + "\"trading_symbol\":\"NIFTY 21200 PE 16 JUN 26\",\"lot_size\":65,"
                            + "\"tick_size\":5.0,\"instrument_type\":\"PE\",\"underlying_key\":\"NSE_INDEX|Nifty 50\","
                            + "\"underlying_symbol\":\"NIFTY\",\"strike_price\":21200.0,\"weekly\":true}]}")));

    List<Leg> legs = client().optionContracts("NSE_INDEX|Nifty 50", LocalDate.of(2026, 6, 16));

    assertThat(legs).hasSize(1);
    Leg leg = legs.get(0);
    assertThat(leg.segment()).isEqualTo("NSE_FO");
    assertThat(leg.instrumentType()).isEqualTo("PE");
    assertThat(leg.strike()).isEqualByComparingTo("21200");
    assertThat(leg.lotSize()).isEqualTo(65);
    assertThat(leg.underlyingSymbol()).isEqualTo("NIFTY");
    assertThat(leg.weekly()).isTrue();
    assertThat(leg.expiry()).isEqualTo(LocalDate.of(2026, 6, 16));
  }

  @Test
  void futureContractsForceFutType() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/expired-instruments/future/contract"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":[{"
                            + "\"segment\":\"NSE_FO\",\"instrument_key\":\"NSE_FO|54452|24-04-2025\","
                            + "\"trading_symbol\":\"NIFTY FUT 24 APR 25\",\"lot_size\":75,"
                            + "\"underlying_symbol\":\"NIFTY\",\"underlying_key\":\"NSE_INDEX|Nifty 50\"}]}")));

    List<Leg> legs = client().futureContracts("NSE_INDEX|Nifty 50", LocalDate.of(2025, 4, 24));

    assertThat(legs).hasSize(1);
    assertThat(legs.get(0).instrumentType()).isEqualTo("FUT");
    assertThat(legs.get(0).strike()).isNull();
  }

  @Test
  void candlesParseOhlcvAndOi() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/expired-instruments/historical-candle/NSEFOTESTKEY/1minute/2026-06-16/2026-06-15"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"candles\":["
                            + "[\"2026-06-16T15:29:00+05:30\",238.95,239.2,238.15,239.1,22750,315185],"
                            + "[\"2026-06-16T15:28:00+05:30\",238.65,239.0,238.1,238.5,105690,330005]]}}")));

    List<Bar> bars =
        client()
            .candles("NSEFOTESTKEY", "1minute", LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16));

    assertThat(bars).hasSize(2);
    Bar first = bars.get(0);
    assertThat(first.open()).isEqualByComparingTo("238.95");
    assertThat(first.high()).isEqualByComparingTo("239.2");
    assertThat(first.low()).isEqualByComparingTo("238.15");
    assertThat(first.close()).isEqualByComparingTo("239.1");
    assertThat(first.volume()).isEqualTo(22750);
    assertThat(first.oi()).isEqualTo(315185L);
    assertThat(first.bucket().toString()).isEqualTo("2026-06-16T15:29+05:30");
  }

  @Test
  void activeCandlesParseDailyOhlcvAndOiFromTheActiveEndpoint() {
    // E1 Market-Movers: the active /v2/historical-candle sibling (no expired-instruments/) reads a live
    // stock FUTURE's DAILY OHLC+OI on-demand — same positional [ts,o,h,l,c,v,oi] array, col 6 = OI.
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/historical-candle/NSEFORELFUT/day/2026-06-16/2026-06-02"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"candles\":["
                            + "[\"2026-06-16T00:00:00+05:30\",1500.0,1525.5,1498.0,1520.0,4500000,12500000],"
                            + "[\"2026-06-13T00:00:00+05:30\",1490.0,1505.0,1485.0,1500.0,3800000,12000000]]}}")));

    List<Bar> bars =
        client()
            .activeCandles(
                "NSEFORELFUT", "day", LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 16));

    assertThat(bars).hasSize(2);
    Bar first = bars.get(0);
    assertThat(first.high()).isEqualByComparingTo("1525.5");
    assertThat(first.close()).isEqualByComparingTo("1520.0");
    assertThat(first.volume()).isEqualTo(4500000);
    assertThat(first.oi()).isEqualTo(12500000L);
    wireMock.verify(
        getRequestedFor(
                urlPathEqualTo("/v2/historical-candle/NSEFORELFUT/day/2026-06-16/2026-06-02"))
            .withHeader("Authorization", equalTo("Bearer test-token")));
  }

  @Test
  void theEquityBackfillLaneParksInTheGuardWindowWhileMarketMoversProceeds()
      throws InterruptedException {
    // EXT-02 finding 2: the full-universe equity backfill uses the BATCH lane (activeCandlesForBatch),
    // so it PARKS in the guard window instead of drawing the live budget; the on-demand E1 market-movers
    // read (activeCandles) is the LIVE lane and still proceeds on the same clock.
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/historical-candle/NSEEQRELIANCE/day/2026-06-16/2026-06-02"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"candles\":["
                            + "[\"2026-06-16T00:00:00+05:30\",1500.0,1525.5,1498.0,1520.0,4500000,12500000]]}}")));

    UpstoxRateLimiter paused = new UpstoxRateLimiter(() -> true); // guard window open → batch paused
    UpstoxExpiredInstrumentsClient client =
        new UpstoxExpiredInstrumentsClient(
            RestClient.builder(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"),
            paused);
    LocalDate from = LocalDate.of(2026, 6, 2);
    LocalDate to = LocalDate.of(2026, 6, 16);

    Thread backfill =
        new Thread(
            () -> {
              try {
                client.activeCandlesForBatch("NSEEQRELIANCE", "day", from, to);
              } catch (RuntimeException ignored) {
                // interrupted at teardown — expected
              }
            });
    backfill.setDaemon(true);
    backfill.start();

    List<Bar> bars = client.activeCandles("NSEEQRELIANCE", "day", from, to);

    assertThat(bars).hasSize(1);
    assertThat(used30m(paused))
        .as("only the live market-movers read drew the budget; the backfill parked")
        .isEqualTo(1);
    assertThat(backfill.isAlive()).as("the equity-backfill lane parked in the guard window").isTrue();

    backfill.interrupt();
    backfill.join(1_000);
  }

  private static int used30m(UpstoxRateLimiter limiter) {
    return limiter.getUsageStats().stream()
        .filter(w -> w.window().equals("30m"))
        .findFirst()
        .orElseThrow()
        .used();
  }
}
