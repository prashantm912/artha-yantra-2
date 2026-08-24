package in.arthayantra.marketdata.nse.analytics;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
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

  /** Synthetic pure-BE symbol (H24) — the series that used to 422 despite having rows. */
  private static final String BE_SYM = "DLVTBESYM";

  /** Synthetic symbol that migrated EQ→BE mid-history, the real NSE surveillance shape. */
  private static final String MIGRATED_SYM = "DLVTMIGSYM";

  /**
   * The two H24 symbols seed in the FAR PAST, and the year is load-bearing rather than arbitrary.
   * A fixture inside the live clock window detonates in unrelated classes (ledger H19), but the
   * usual escape — the year-2198 pattern at {@code DataQualityReportIntegrationTest:43-44} — is
   * the WRONG direction for this table: a future date becomes the global {@code max(trade_date)},
   * which is exactly what the as-of-latest queries in this very file key on
   * ({@code EquityReturnsService:141}, {@code EquitySectorService:197},
   * {@code EquityIndexContributionService:250}, all EQ-only, plus the EQ+BE
   * {@code TrendTemplateService:81} / {@code ManasScreenService:84}). A far-PAST date can never
   * win a {@code max()}, so it is inert for every one of them; the delivery endpoint itself has no
   * date bound at all (it takes the newest N for one symbol), so history that old still serves.
   */
  private static final int H24_YEAR = 1998;

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
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", BE_SYM);
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", MIGRATED_SYM);
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
    insertBhavEq(SYM, d, prevClose, open, high, low, close, delivPer, delivQty, ttq);
  }

  /** A deliverable EQ row: both delivery columns populated. */
  private void insertBhavEq(
      String symbol,
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
        symbol,
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

  /**
   * A BE row. NSE publishes NO delivery figures for the BE series, so both delivery columns are
   * ABSENT rather than zero — measured 2026-08-17 against the live table: 0 of 54,384 BE rows carry
   * either {@code deliv_per} or {@code deliv_qty}, across the table's whole 2025-06-20..2026-08-14
   * span (V014:18-19 documents both as "null for non-deliverable rows"). Omitting the columns
   * reproduces that shape exactly; writing 0 would fabricate a delivery claim the feed never made.
   */
  private void insertBhavBe(
      String symbol,
      LocalDate d,
      String prevClose,
      String open,
      String high,
      String low,
      String close,
      long ttq) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy "
            + "(trade_date, symbol, series, prev_close, open_price, high_price, low_price, "
            + " close_price, ttl_trd_qnty) "
            + "VALUES (?,?,?,?::numeric,?::numeric,?::numeric,?::numeric,?::numeric,?) "
            + "ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(d),
        symbol,
        "BE",
        prevClose,
        open,
        high,
        low,
        close,
        ttq);
  }

  @Test
  void deliveryReturnsRowsForBeSeriesSymbol() throws Exception {
    // H24: this endpoint filtered `series = 'EQ'`, so a BE symbol threw a hard 422 DATA_GAP despite
    // having rows — an owner-visible outage on the BE names the swing books hold.
    insertBhavBe(BE_SYM, LocalDate.of(H24_YEAR, 3, 10), "95", "96", "101", "94", "100", 9000);
    insertBhavBe(BE_SYM, LocalDate.of(H24_YEAR, 3, 11), "100", "101", "106", "99", "104", 9500);

    mockMvc
        .perform(get("/api/v1/market/equity/delivery").param("symbol", BE_SYM).param("days", "15"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value(BE_SYM))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].date").value(H24_YEAR + "-03-11")) // newest first
        // Every OHLC-derived field still computes for a BE row — the series carries real prices.
        .andExpect(jsonPath("$.items[0].ltpChangePct").value("4.00")) // (104-100)/100*100
        .andExpect(jsonPath("$.items[0].close").value("104.0000"))
        // ...but the two delivery cells are ABSENT, not zero. Serving 0 would assert that nothing
        // went to delivery; the truth is that NSE published no figure for this series at all. The
        // UI renders the null as an em-dash (DeliveryDataPage.tsx:65).
        .andExpect(jsonPath("$.items[0].deliveryPct").value(nullValue()))
        .andExpect(jsonPath("$.items[0].deliveryQty").value(nullValue()))
        // ttl_trd_qnty IS published for BE, so it must NOT be collateral damage of the above.
        .andExpect(jsonPath("$.items[0].totalTradedQty").value(9500));
  }

  @Test
  void deliveryMergesHistoryAcrossEqToBeMigration() throws Exception {
    // The real NSE surveillance shape: a name trades EQ, is moved to BE, and keeps printing. It is
    // ONE continuous instrument, so the page must show ONE unbroken series rather than truncating
    // at the migration. The PK is (trade_date, symbol, series), so the two halves coexist happily.
    insertBhavEq(
        MIGRATED_SYM, LocalDate.of(H24_YEAR, 4, 6), "95", "96", "101", "94", "100", "50.00", 4000, 9000);
    insertBhavEq(
        MIGRATED_SYM, LocalDate.of(H24_YEAR, 4, 7), "100", "101", "106", "99", "104", "52.00", 4500, 9500);
    insertBhavBe(MIGRATED_SYM, LocalDate.of(H24_YEAR, 4, 8), "104", "105", "110", "103", "108", 10000);
    insertBhavBe(MIGRATED_SYM, LocalDate.of(H24_YEAR, 4, 9), "108", "109", "112", "107", "111", 10500);

    mockMvc
        .perform(
            get("/api/v1/market/equity/delivery").param("symbol", MIGRATED_SYM).param("days", "15"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4)) // spans BOTH series, not just one
        .andExpect(jsonPath("$.items[0].date").value(H24_YEAR + "-04-09")) // newest first...
        .andExpect(jsonPath("$.items[3].date").value(H24_YEAR + "-04-06")) // ...oldest last
        // Each half keeps its own delivery semantics across the seam — proof the merge is a real
        // union rather than one series' values being carried over the other's rows.
        .andExpect(jsonPath("$.items[0].deliveryPct").value(nullValue())) // BE half
        .andExpect(jsonPath("$.items[3].deliveryPct").value("50.00")) // EQ half
        .andExpect(jsonPath("$.items[3].deliveryQty").value(4000));
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
