package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Options Chart IT (plan §options/options-chart, mock): per-leg premium candles are read cache-first from
 * the generic candle path (the mock fabricates 1m option-leg bars after a sync), and the OI/IV line rides
 * OptionsSnapshotReader.strikeSeries. The load-bearing invariant is the two-table session alignment:
 * candle buckets (1m-aggregated) + snapshot buckets (re-keyed) share one 09:15-IST grid, so OI/IV
 * left-join onto the matching candle — a candle with no snapshot carries null. Both legs ride one fetch.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class OptionsOiChartIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String UNDERLYING = "NIFTY 50";
  private static final LocalDate EXPIRY = LocalDate.parse("2026-06-16");
  private static final String SESSION = "2026-06-15";

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private InstrumentRepository instruments;
  @Autowired private OptionsOiChartService optionsOiChartService;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seedChain() {
    syncService.runSync();
  }

  /** A listed strike near mid-ladder (CE+PE both present after the sync). */
  private BigDecimal midStrike() {
    List<BigDecimal> strikes = instruments.strikes(UNDERLYING, EXPIRY);
    assertThat(strikes).isNotEmpty();
    return strikes.get(strikes.size() / 2);
  }

  @Test
  void chartMergesOiAndIvOntoBothLegsOnTheSharedGrid() {
    BigDecimal strike = midStrike();
    // Seed CE + PE snapshots at the first 5m bucket (09:15) with distinct oi + iv at the SAME strike scale.
    OffsetDateTime b0 = OffsetDateTime.parse(SESSION + "T09:16:40+05:30");
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, b0, UNDERLYING, EXPIRY, strike.toPlainString(), "CE", "100.00", 137250L, 0L, 1000L, "13.03");
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc, b0, UNDERLYING, EXPIRY, strike.toPlainString(), "PE", "120.00", 140820L, 0L, 1000L, "19.20");

    OptionsOiChartService.OptOiChart chart =
        optionsOiChartService.chart(UNDERLYING, EXPIRY, strike, 5, LocalDate.parse(SESSION));

    assertThat(chart.interval()).isEqualTo("5m");
    assertThat(chart.strike()).isEqualByComparingTo(strike);
    assertThat(chart.ceTradingsymbol()).isNotBlank();
    assertThat(chart.peTradingsymbol()).isNotBlank();
    assertThat(chart.ce()).as("mock fabricates 1m CE bars on a trading day").isNotEmpty();
    assertThat(chart.pe()).isNotEmpty();

    // The 09:15 candle of each leg carries the merged OI + IV; OHLC is well-ordered.
    OptionsOiChartService.OptCandle ce0 = chart.ce().get(0);
    assertThat(ce0.time().toString()).startsWith(SESSION + "T09:15");
    assertThat(ce0.oi()).isEqualTo(137250L);
    assertThat(ce0.iv()).isEqualByComparingTo("13.03");
    OptionsOiChartService.OptCandle pe0 = chart.pe().get(0);
    assertThat(pe0.oi()).isEqualTo(140820L);
    assertThat(pe0.iv()).isEqualByComparingTo("19.20");
    for (OptionsOiChartService.OptCandle c : chart.ce()) {
      assertThat(c.high()).isGreaterThanOrEqualTo(c.low());
      assertThat(c.high()).isGreaterThanOrEqualTo(c.open());
      assertThat(c.high()).isGreaterThanOrEqualTo(c.close());
      assertThat(c.low()).isLessThanOrEqualTo(c.open());
    }
    // A later candle (no snapshot at that bucket) carries null oi + null iv (the left-join).
    assertThat(chart.ce()).anySatisfy(c -> assertThat(c.oi()).isNull());
    assertThat(chart.ce()).anySatisfy(c -> assertThat(c.iv()).isNull());
  }

  @Test
  void restEnvelopeServesBothLegsAsDecimalStrings() throws Exception {
    BigDecimal strike = midStrike();
    OptionsSnapshotReaderIntegrationTest.insertRow(
        jdbc,
        OffsetDateTime.parse(SESSION + "T09:16:40+05:30"),
        UNDERLYING,
        EXPIRY,
        strike.toPlainString(),
        "CE",
        "100.00",
        137250L,
        0L,
        1000L,
        "13.03");

    mockMvc
        .perform(
            get("/api/v1/market/options/options-chart")
                .param("name", UNDERLYING)
                .param("mode", "history")
                .param("date", SESSION)
                .param("expiry", EXPIRY.toString())
                .param("strike", strike.toPlainString())
                .param("interval", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.interval").value("5m"))
        .andExpect(jsonPath("$.ce").isArray())
        .andExpect(jsonPath("$.pe").isArray())
        .andExpect(jsonPath("$.ce[0].open").isString())
        .andExpect(jsonPath("$.ce[0].oi").value(137250));
  }

  @Test
  void unlistedStrikeIs422() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/options-chart")
                .param("name", UNDERLYING)
                .param("mode", "history")
                .param("date", SESSION)
                .param("expiry", EXPIRY.toString())
                .param("strike", "99999999")
                .param("interval", "5"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }

  @Test
  void offSetIntervalIs400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/options/options-chart")
                .param("name", UNDERLYING)
                .param("mode", "history")
                .param("date", SESSION)
                .param("expiry", EXPIRY.toString())
                .param("strike", midStrike().toPlainString())
                .param("interval", "2"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_INTERVAL_UNSUPPORTED"));
  }
}
