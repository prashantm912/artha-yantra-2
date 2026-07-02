package in.arthayantra.marketdata.futures.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Futures OI Chart IT (plan §futures/oi-chart, mock): the candlestick PRICE is read cache-first from the
 * generic candle path (the mock fabricates 1m FUT bars on a trading day — no new capture), and the OI
 * LINE rides {@link FuturesSnapshotReader#series}. The load-bearing invariant is the TWO-TABLE session
 * alignment: candle buckets (1m-aggregated) and OI buckets (snapshots, re-keyed) share one 09:15-IST grid,
 * so OI left-joins onto the matching candle — a candle with no OI sample carries null, and an OI sample
 * with no candle (e.g. post-close) creates no phantom candle.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class FuturesOiChartIntegrationTest extends MarketDataIntegrationTestBase {

  // After a sync the mock master resolves NIFTY 50 → the NIFTY26JUNFUT front future; 2026-06-15 is an
  // open Monday session (same fixture the straddle + futures-slice ITs use).
  private static final String UNDERLYING = "NIFTY 50";
  private static final String SYMBOL = "NIFTY26JUNFUT";
  private static final LocalDate FRONT_EXPIRY = LocalDate.parse("2026-06-25");
  private static final String SESSION = "2026-06-15";

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private FuturesOiChartService oiChartService;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seedChain() {
    syncService.runSync(); // resolves the NIFTY 50 FUT ladder (front/next/far)
  }

  /** Seeds one OI snapshot for the front contract (the candle data comes from the mock candle path). */
  private void insertOi(OffsetDateTime ts, long oi, long oiChange) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        UNDERLYING,
        SYMBOL,
        java.sql.Date.valueOf(FRONT_EXPIRY),
        "24100.00",
        1000L,
        oi,
        oiChange);
  }

  @Test
  void chartMergesOiOntoCandlesOnTheSharedSessionGrid() {
    insertOi(OffsetDateTime.parse(SESSION + "T09:16:40+05:30"), 1_000_000L, 5000L); // first 5m bucket
    insertOi(OffsetDateTime.parse(SESSION + "T15:45:00+05:30"), 999L, 0L); // post-close: NO candle there

    FuturesOiChartService.FutOiChart chart =
        oiChartService.chart(UNDERLYING, null, 5, LocalDate.parse(SESSION));

    assertThat(chart.tradingsymbol()).isEqualTo(SYMBOL);
    assertThat(chart.expiry()).isEqualTo(FRONT_EXPIRY);
    assertThat(chart.interval()).isEqualTo("5m");
    assertThat(chart.items()).as("mock fabricates 1m FUT bars on a trading day").isNotEmpty();
    assertThat(chart.items())
        .isSortedAccordingTo(
            Comparator.comparing(FuturesOiChartService.FutOiCandle::time)); // ascending

    // The 09:15 candle carries the merged OI; every candle's OHLC is well-ordered.
    FuturesOiChartService.FutOiCandle first = chart.items().get(0);
    assertThat(first.time().toString()).startsWith(SESSION + "T09:15");
    assertThat(first.oi()).isEqualTo(1_000_000L);
    for (FuturesOiChartService.FutOiCandle c : chart.items()) {
      assertThat(c.high()).isGreaterThanOrEqualTo(c.low());
      assertThat(c.high()).isGreaterThanOrEqualTo(c.open());
      assertThat(c.high()).isGreaterThanOrEqualTo(c.close());
      assertThat(c.low()).isLessThanOrEqualTo(c.open());
      assertThat(c.volume()).isGreaterThanOrEqualTo(0L);
    }
    // candle-with-no-OI → null (only 09:15 was seeded).
    assertThat(chart.items()).anySatisfy(c -> assertThat(c.oi()).isNull());
    // OI-without-candle (the 15:45 post-close sentinel) created no phantom candle.
    assertThat(chart.items()).noneSatisfy(c -> assertThat(c.oi()).isEqualTo(999L));
  }

  @Test
  void oneMinuteIntervalIsSupported() {
    // The 1-min option is the page's defining feature (candles aggregate from 1m base).
    FuturesOiChartService.FutOiChart chart =
        oiChartService.chart(UNDERLYING, null, 1, LocalDate.parse(SESSION));
    assertThat(chart.interval()).isEqualTo("1m");
    assertThat(chart.items()).isNotEmpty();
  }

  @Test
  void restEnvelopeServesDecimalStringsAndMergedOi() throws Exception {
    insertOi(OffsetDateTime.parse(SESSION + "T09:16:40+05:30"), 1_000_000L, 5000L);

    mockMvc
        .perform(
            get("/api/v1/market/futures/oi-chart")
                .param("name", UNDERLYING)
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tradingsymbol").value(SYMBOL))
        .andExpect(jsonPath("$.interval").value("5m"))
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].open").isString())
        .andExpect(jsonPath("$.items[0].oi").value(1000000));
  }

  @Test
  void unknownUnderlyingIs422() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/futures/oi-chart")
                .param("name", "NOSUCHINDEX")
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "5"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }

  @Test
  void offSetIntervalIs400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/futures/oi-chart")
                .param("name", UNDERLYING)
                .param("mode", "history")
                .param("date", SESSION)
                .param("interval", "7"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }
}
