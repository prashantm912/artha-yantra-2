package in.arthayantra.marketdata.nse.preopen;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Audit §9.4 (Wave 5): the equity pre-open full-scanner capture + preserved read. */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class PreOpenEquityScanIntegrationTest extends MarketDataIntegrationTestBase {

  // Unique symbol — the IT singleton DB has no per-method cleanup.
  private static final String SYM = "POESCAN";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", SYM);
    jdbc.update("DELETE FROM instruments WHERE tradingsymbol = ?", SYM + "26JULFUT");
  }

  /** Canned pre-open quote: 107 vs prev close 100 (+7.00%), above the prev high 105 → 'H' break. */
  @TestConfiguration
  static class CannedQuotes {
    @Bean
    @Primary
    QuoteGateway cannedQuoteGateway() {
      return (Collection<InstrumentKey> keys) ->
          Map.of(
              new InstrumentKey("NSE", SYM),
              new QuoteGateway.Quote(
                  new InstrumentKey("NSE", SYM),
                  new BigDecimal("107.00"),
                  OffsetDateTime.parse("2026-07-02T09:09:30+05:30")));
    }
  }

  @Test
  void captureThenReadThePreservedScan() throws Exception {
    LocalDate today = LocalDate.now(Ist.ZONE);
    // F&O universe membership: an active NFO FUT whose name has an EQ bhavcopy row.
    jdbc.update(
        "INSERT INTO instruments (exchange, tradingsymbol, name, instrument_type, is_active) "
            + "VALUES ('NFO', ?, ?, 'FUT', true) ON CONFLICT DO NOTHING",
        SYM + "26JULFUT",
        SYM);
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, prev_close, high_price, "
            + "low_price, close_price) VALUES (?, ?, 'EQ', 98, 105, 96, 100) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(today.minusDays(1)),
        SYM);

    mockMvc
        .perform(post("/api/v1/market/equity/pre-open-scan/capture"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.captured").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

    mockMvc
        .perform(get("/api/v1/market/equity/pre-open-scan"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.date").value(today.toString()))
        .andExpect(jsonPath("$.items[?(@.symbol == '" + SYM + "')].preOpenPrice").value(hasFirst("107.0000")))
        .andExpect(jsonPath("$.items[?(@.symbol == '" + SYM + "')].changePct").value(hasFirst("7.00")))
        .andExpect(jsonPath("$.items[?(@.symbol == '" + SYM + "')].prevDayBreak").value(hasFirst("H")))
        .andExpect(jsonPath("$.dates").value(org.hamcrest.Matchers.hasItem(today.toString())));

    // history read of an uncaptured day = empty items (not an error)
    mockMvc
        .perform(get("/api/v1/market/equity/pre-open-scan").param("date", "2020-01-01"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  private static org.hamcrest.Matcher<Iterable<? super String>> hasFirst(String v) {
    return org.hamcrest.Matchers.hasItem(v);
  }
}
