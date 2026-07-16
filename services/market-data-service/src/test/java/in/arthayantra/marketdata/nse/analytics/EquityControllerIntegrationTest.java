package in.arthayantra.marketdata.nse.analytics;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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

  /** Synthetic and unique to this class — safe to purge whole-series. */
  private static final String SYM = "DLVTSYM";

  /**
   * REAL, sector-mapped tickers: this class needs them because its queries rank mapped symbols, so
   * they are NOT exclusively ours. {@code BreadthControllerIntegrationTest:52-56} seeds RELIANCE /
   * TCS / INFY at 2024-06-12 and has no cleanup of its own. Every seed here lands within ~40 days of
   * today, so the purge is floored well clear of that — an unbounded delete would reach into another
   * class's fixture on the shared singleton DB, which is the very contamination this test file is
   * being fixed for.
   */
  private static final List<String> OWN_REAL_SYMBOLS =
      List.of("AARTIIND", "WIPRO", "SUNPHARMA", "CIPLA", "RELIANCE");

  private static final int OWN_SEED_FLOOR_DAYS = 60;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", SYM);
    Date floor = Date.valueOf(LocalDate.now(Ist.ZONE).minusDays(OWN_SEED_FLOOR_DAYS));
    for (String symbol : OWN_REAL_SYMBOLS) {
      jdbc.update(
          "DELETE FROM nse_eod_bhavcopy WHERE symbol = ? AND trade_date >= ?", symbol, floor);
    }
  }

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
    // rn 1..6 by recency; the LTP row (rn1) must sit on the max accrued session (today IST) so it
    // survives the as-of-latest Drop (audit D3 — a symbol whose latest row predates the badge date is
    // excluded). AARTIIND is in the static sector map (the screener restricts to mapped symbols).
    String sym = "AARTIIND";
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", sym); // own the symbol (shared DB)
    LocalDate max = LocalDate.now(Ist.ZONE);
    insertClose(max.minusDays(9), sym, "88"); // rn6 → 1W base
    insertClose(max.minusDays(5), sym, "101");
    insertClose(max.minusDays(4), sym, "103");
    insertClose(max.minusDays(3), sym, "105");
    insertClose(max.minusDays(1), sym, "100"); // rn2 → 1D base
    insertClose(max, sym, "110"); // rn1 → LTP, on the max session

    mockMvc
        .perform(get("/api/v1/market/equity/returns"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.symbol=='AARTIIND')].r1d", hasItem("10.00"))) // (110-100)/100
        .andExpect(jsonPath("$.items[?(@.symbol=='AARTIIND')].r1w", hasItem("25.00"))); // (110-88)/88
  }

  @Test
  void returnsDropsSymbolsWhoseLatestPredatesTheAsOfSession() throws Exception {
    // A thin/delisted name whose newest EQ row is older than the max accrued session must NOT appear
    // (audit D3 Drop): otherwise it shows a stale close under the "as of <latest>" badge. WIPRO is
    // sector-mapped. Seed it OLD; seed a fresh mapped name (SUNPHARMA) on the max session so the
    // global max is today and WIPRO is provably stale.
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol IN ('WIPRO','SUNPHARMA')");
    LocalDate max = LocalDate.now(Ist.ZONE);
    insertClose(max.minusDays(40), "WIPRO", "300"); // stale — latest row 40 days old
    insertDay(max, "SUNPHARMA", "500", "510"); // fresh — pins the as-of to today

    mockMvc
        .perform(get("/api/v1/market/equity/returns"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.symbol=='SUNPHARMA')]").exists())
        .andExpect(jsonPath("$.items[?(@.symbol=='WIPRO')]").doesNotExist());
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

  /** Deletes then seeds one symbol on the max accrued session (today IST), so the row survives the
   * as-of-latest Drop and can't collide with another method's same-symbol seed in the shared DB. */
  private void seedToday(String sym, String prevClose, String close) {
    LocalDate today = LocalDate.now(Ist.ZONE);
    jdbc.update(
        "DELETE FROM nse_eod_bhavcopy WHERE symbol = ? AND trade_date = ?",
        sym,
        java.sql.Date.valueOf(today));
    insertDay(today, sym, prevClose, close);
  }

  @Test
  void sectorStatsGroupsConstituentsBySector() throws Exception {
    // Seed on the max accrued session (today IST) so the row survives the as-of-latest Drop (audit D3).
    // CIPLA (Healthcare) — its own symbol, so it can't collide with the returns test's AARTIIND@today.
    seedToday("CIPLA", "100", "105"); // +5%

    mockMvc
        .perform(get("/api/v1/market/equity/sector-stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stocks[?(@.symbol=='CIPLA')].sector", hasItem("Healthcare")))
        .andExpect(jsonPath("$.stocks[?(@.symbol=='CIPLA')].changePct", hasItem("5.00")))
        .andExpect(jsonPath("$.sectors[?(@.sector=='Healthcare')].total").exists());
  }

  @Test
  void sectorHeatmapPicksIndexConstituents() throws Exception {
    // Same RELIANCE@today seed as the contribution test (identical values → no shared-DB collision).
    seedToday("RELIANCE", "1000", "1020"); // NIFTY 50 member

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
    // RELIANCE NIFTY-50 weight = 8.27; +2% → contribution = 8.27 * 2 / 100 = 0.1654. On the max
    // accrued session (today IST) so it survives the as-of-latest Drop; same RELIANCE@today seed as
    // the heatmap test.
    seedToday("RELIANCE", "1000", "1020");

    mockMvc
        .perform(get("/api/v1/market/equity/index-contribution").param("name", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.index").value("NIFTY 50"))
        .andExpect(jsonPath("$.advances[?(@.symbol=='RELIANCE')].changePct", hasItem("2.00")))
        .andExpect(jsonPath("$.advances[?(@.symbol=='RELIANCE')].contribution", hasItem("0.1654")));
  }
}
