package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import in.arthayantra.marketdata.upstox.StockChainCandleSource;
import in.arthayantra.marketdata.upstox.UpstoxExpiredInstrumentsClient.Bar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Audit §9.3 (Wave 5): the on-demand stock-chain warm — canned Upstox 1m bars land as
 * {@code options_chain_snapshots} rows and the EXISTING readers serve the stock unchanged.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class StockChainWarmIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String STOCK = "WARMSTK";
  private static final LocalDate EXPIRY = LocalDate.of(2026, 7, 28);
  private static final LocalDate DAY = LocalDate.of(2026, 6, 24);

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired StockChainWarmService service;

  private static Bar bar(String startIst, String close, long volume, Long oi) {
    return new Bar(
        OffsetDateTime.parse(startIst + "+05:30"),
        new BigDecimal(close),
        new BigDecimal(close),
        new BigDecimal(close),
        new BigDecimal(close),
        volume,
        oi);
  }

  /** Canned bars: newest-first (the real endpoints' order) so the ASC re-sort is exercised. */
  @TestConfiguration
  static class CannedSource {
    @Bean
    @Primary
    StockChainCandleSource cannedStockChainCandleSource() {
      Map<String, List<Bar>> byLeg =
          Map.of(
              "1500CE",
              List.of(
                  bar("2026-06-24T09:16:00", "31.00", 500, 12_000L),
                  bar("2026-06-24T09:15:00", "30.00", 400, 10_000L)),
              "1500PE",
              List.of(
                  bar("2026-06-24T09:16:00", "24.00", 300, 8_500L),
                  bar("2026-06-24T09:15:00", "25.00", 200, 9_000L)));
      return new StockChainCandleSource() {
        @Override
        public boolean available() {
          return true;
        }

        @Override
        public List<Bar> optionDayBars(
            String underlying, String optionType, LocalDate expiry, BigDecimal strike, LocalDate day, boolean today) {
          return byLeg.getOrDefault(strike.stripTrailingZeros().toPlainString() + optionType, List.of());
        }

        @Override
        public List<Bar> equityDayBars(String symbol, LocalDate day, boolean today) {
          return List.of(
              bar("2026-06-24T09:16:00", "1502.00", 0, null),
              bar("2026-06-24T09:15:00", "1500.00", 0, null));
        }
      };
    }
  }

  @Test
  void warmPersistsDerivedRowsAndTheExistingReadersServeTheStock() throws Exception {
    // the canonical instruments master lists the legs the warm iterates
    jdbc.update(
        "INSERT INTO instruments (exchange, tradingsymbol, name, instrument_type, expiry, strike, is_active) "
            + "VALUES ('NFO', ?, ?, 'CE', ?, 1500, true), ('NFO', ?, ?, 'PE', ?, 1500, true) "
            + "ON CONFLICT DO NOTHING",
        STOCK + "26JUL1500CE", STOCK, java.sql.Date.valueOf(EXPIRY),
        STOCK + "26JUL1500PE", STOCK, java.sql.Date.valueOf(EXPIRY));

    // synchronous core (the controller wraps it in the single-flight executor)
    StockChainWarmService.WarmStatus before = service.status(STOCK, EXPIRY, DAY);
    assertThat(before.rowsInStore()).isZero();
    int persisted = service.warm(STOCK, EXPIRY, DAY, new StockChainWarmService.Progress());
    assertThat(persisted).isEqualTo(4); // 2 legs × 2 bars

    // rows landed with the derived provenance, bar-END timestamps and bar-over-bar oi_change
    Long oiChange =
        jdbc.queryForObject(
            "SELECT oi_change FROM options_chain_snapshots WHERE underlying = ? AND option_type = 'CE' "
                + "AND ts = ?",
            Long.class,
            STOCK,
            java.sql.Timestamp.from(OffsetDateTime.parse("2026-06-24T09:17:00+05:30").toInstant()));
    assertThat(oiChange).isEqualTo(2_000L); // 12000 − 10000, stamped at the 09:16 bar's END
    assertThat(
            jdbc.queryForObject(
                "SELECT DISTINCT source FROM options_chain_snapshots WHERE underlying = ?",
                String.class,
                STOCK))
        .isEqualTo("UPSTOX_1M");

    // the EXISTING trending reader serves the stock (history mode on the warmed day; 1m so the two
    // bar-END readings label consecutive windows under the T2 end-of-window convention)
    mockMvc
        .perform(
            get("/api/v1/market/options/trending")
                .param("name", STOCK)
                .param("expiry", EXPIRY.toString())
                .param("interval", "1m")
                .param("mode", "history")
                .param("date", DAY.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        // first reading (ts 09:16) labels the 09:15 window; CE 10000 + PE 9000
        .andExpect(jsonPath("$.items[0].totalOi").value(19_000))
        .andExpect(jsonPath("$.items[0].spot").value(org.hamcrest.Matchers.startsWith("1500")))
        .andExpect(jsonPath("$.items[1].totalOi").value(20_500));

    // idempotent: a re-warm adds nothing (PK conflict → DO NOTHING)
    assertThat(service.warm(STOCK, EXPIRY, DAY, new StockChainWarmService.Progress())).isZero();
  }

  @Test
  void warmStatusEndpointIsTypedAndFrontExpiryResolves() throws Exception {
    jdbc.update(
        "INSERT INTO instruments (exchange, tradingsymbol, name, instrument_type, expiry, strike, is_active) "
            + "VALUES ('NFO', ?, 'WARMFRONT', 'CE', ?, 100, true) ON CONFLICT DO NOTHING",
        "WARMFRONT99CE",
        java.sql.Date.valueOf(LocalDate.now().plusDays(20)));

    mockMvc
        .perform(get("/api/v1/market/options/stock-chain/warm").param("name", "WARMFRONT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value("WARMFRONT"))
        .andExpect(jsonPath("$.state").value("IDLE"))
        .andExpect(jsonPath("$.rowsInStore").value(0));
  }
}
