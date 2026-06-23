package in.arthayantra.marketdata.nse.analytics;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class EquityControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  // Unique symbol — the IT singleton DB has no per-method cleanup.
  private static final String SYM = "DLVTSYM";

  private void insertBhav(
      LocalDate d,
      String prevClose,
      String open,
      String high,
      String low,
      String close,
      String delivPer,
      long delivQty,
      long ttq) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy "
            + "(trade_date, symbol, series, prev_close, open_price, high_price, low_price, "
            + " close_price, deliv_per, deliv_qty, ttl_trd_qnty) "
            + "VALUES (?,?,?,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?,?) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        SYM,
        "EQ",
        prevClose,
        open,
        high,
        low,
        close,
        delivPer,
        delivQty,
        ttq);
  }

  @Test
  void deliverySeriesIsNewestFirstWithComputedChange() throws Exception {
    insertBhav(LocalDate.of(2026, 6, 10), "95", "96", "101", "94", "100", "50.00", 4000, 9000);
    insertBhav(LocalDate.of(2026, 6, 11), "100", "101", "106", "99", "104", "52.00", 4500, 9500);
    insertBhav(LocalDate.of(2026, 6, 12), "100", "101", "110", "99", "108", "55.50", 5000, 10000);

    mockMvc
        .perform(get("/api/v1/market/equity/delivery").param("symbol", SYM).param("days", "15"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value(SYM))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].date").value("2026-06-12")) // newest first
        .andExpect(jsonPath("$.items[0].ltpChangePct").value("8.00")) // (108-100)/100*100
        .andExpect(jsonPath("$.items[0].deliveryQty").value(5000));
  }

  @Test
  void deliveryIs422WhenSymbolHasNoBhavcopy() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/equity/delivery").param("symbol", "NOSUCHSYM").param("days", "15"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }

  private void insertClose(LocalDate d, String sym, String close) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, close_price) "
            + "VALUES (?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        sym,
        "EQ",
        close);
  }

  @Test
  void returnsComputesWindowsFromRankedCloses() throws Exception {
    // 6 sessions (rn 1..6 by recency): rn1=110 (latest), rn2=100 (1D base), rn6=88 (1W base).
    // AARTIIND is in the static sector map (the screener restricts to mapped symbols).
    String sym = "AARTIIND";
    insertClose(LocalDate.of(2026, 6, 5), sym, "88"); // rn6 → 1W
    insertClose(LocalDate.of(2026, 6, 8), sym, "101");
    insertClose(LocalDate.of(2026, 6, 9), sym, "103");
    insertClose(LocalDate.of(2026, 6, 10), sym, "105");
    insertClose(LocalDate.of(2026, 6, 11), sym, "100"); // rn2 → 1D
    insertClose(LocalDate.of(2026, 6, 12), sym, "110"); // rn1 → LTP

    mockMvc
        .perform(get("/api/v1/market/equity/returns"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.symbol=='AARTIIND')].r1d", hasItem("10.00"))) // (110-100)/100
        .andExpect(jsonPath("$.items[?(@.symbol=='AARTIIND')].r1w", hasItem("25.00"))); // (110-88)/88
  }

  private void insertDay(LocalDate d, String sym, String prevClose, String close) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, prev_close, close_price) "
            + "VALUES (?,?,?,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        sym,
        "EQ",
        prevClose,
        close);
  }

  @Test
  void sectorStatsGroupsConstituentsBySector() throws Exception {
    // Fresh, newest date so these rows are rn=1 (the latest session) regardless of other ITs.
    insertDay(LocalDate.of(2026, 6, 20), "AARTIIND", "100", "105"); // Chemicals, +5%

    mockMvc
        .perform(get("/api/v1/market/equity/sector-stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stocks[?(@.symbol=='AARTIIND')].sector", hasItem("Chemicals")))
        .andExpect(jsonPath("$.stocks[?(@.symbol=='AARTIIND')].changePct", hasItem("5.00")))
        .andExpect(jsonPath("$.sectors[?(@.sector=='Chemicals')].total").exists());
  }

  @Test
  void sectorHeatmapPicksIndexConstituents() throws Exception {
    insertDay(LocalDate.of(2026, 6, 20), "RELIANCE", "1000", "1010"); // NIFTY 50 member, +1%

    mockMvc
        .perform(get("/api/v1/market/equity/sector-heatmap").param("name", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.index").value("NIFTY 50"))
        .andExpect(
            jsonPath(
                "$.tiles[?(@.symbol=='RELIANCE')].sector",
                hasItem("Oil Gas & Consumable Fuels")));
  }

  @Test
  void indexContributionWeightsTheChange() throws Exception {
    // RELIANCE NIFTY-50 weight = 8.27; +2% → contribution = 8.27 * 2 / 100 = 0.1654.
    insertDay(LocalDate.of(2026, 6, 21), "RELIANCE", "1000", "1020");

    mockMvc
        .perform(get("/api/v1/market/equity/index-contribution").param("name", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.index").value("NIFTY 50"))
        .andExpect(jsonPath("$.advances[?(@.symbol=='RELIANCE')].changePct", hasItem("2.00")))
        .andExpect(jsonPath("$.advances[?(@.symbol=='RELIANCE')].contribution", hasItem("0.1654")));
  }
}
