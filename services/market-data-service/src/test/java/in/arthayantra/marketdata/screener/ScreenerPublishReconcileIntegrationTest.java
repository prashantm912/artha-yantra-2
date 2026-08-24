package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two review Criticals, pinned by execution rather than by argument.
 *
 * <p><b>1 — the guard must REMOVE, not merely stop re-inserting.</b> Dropping stale symbols in
 * memory while the repository only upserts leaves a row an earlier run already wrote still
 * queryable at the same {@code screen_date}; the scheduler's watermark dedup then prevents the
 * recompute that might have overwritten it, so the observable owner-facing state never changes — on
 * the exact row (JBCHEPHARM) that motivated the guard. The discriminating fixture therefore
 * <b>pre-seeds a stale screen row before the run</b>: a fixture that starts from an empty table
 * cannot reach the state and so cannot fail on it.
 *
 * <p><b>2 — an explicit {@code asOf} ahead of the data must not become the label.</b> With
 * {@code asOf} ahead, every symbol sits on the last real bar, coverage reads 100% and the coverage
 * floor passes — so the floor cannot catch this. Without the effective-screen-date fix the run
 * persists the older rows under the REQUESTED date, which is precisely the
 * stale-close-under-a-current-badge mislabelling the guard exists to prevent. Asserted on the
 * PERSISTED {@code screen_date}, not on reasoning.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
      // Coverage floor left at its 80% default: the fixture is 5 fresh + 1 stale = 83.3%, above the
      // floor, so both tests exercise the real production threshold. Test 2 in particular depends on
      // the floor PASSING — that is the whole point of the door it leaves open.
    })
@AutoConfigureMockMvc
class ScreenerPublishReconcileIntegrationTest extends MarketDataIntegrationTestBase {

  private static final List<String> FRESH =
      List.of("RCNFRESH1", "RCNFRESH2", "RCNFRESH3", "RCNFRESH4", "RCNFRESH5");
  private static final String STALE = "RCNSTALE1";
  private static final int DAYS = 260;
  private static final int STALE_SESSIONS = 5;

  /** Beyond every sibling fixture's window, so this class's rows define the table's max date. */
  private static final LocalDate DATA_END = LocalDate.of(2026, 9, 30);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mockMvc;

  private static List<String> allSymbols() {
    List<String> all = new ArrayList<>(FRESH);
    all.add(STALE);
    return all;
  }

  private void purge() {
    for (String s : allSymbols()) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_setups WHERE symbol=?", s);
    }
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    FRESH.forEach(s -> seedSeries(s, DAYS));
    seedSeries(STALE, DAYS - STALE_SESSIONS);
  }

  private void seedSeries(String symbol, int bars) {
    List<Object[]> batch = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      double px = Math.round((100.0 + 100.0 * i / (DAYS - 1)) * 100.0) / 100.0;
      batch.add(new Object[] {DATA_END.minusDays(DAYS - 1L - i), symbol, px, px, px, 100_000L});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,close_price,high_price,low_price,"
            + "ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?) ON CONFLICT DO NOTHING",
        batch);
  }

  /** Writes a screen row the way a pre-guard run would have — the JBCHEPHARM state. */
  private void seedStaleScreenRow(LocalDate screenDate, String symbol) {
    jdbc.update(
        "INSERT INTO minervini_screen_results(screen_date, symbol, exchange, close_price,"
            + " gate1,gate2,gate3,gate4,gate5,gate6,gate7,gate8, gates_passed, passes_all,"
            + " computed_at) VALUES (?,?,'NSE',?, true,true,true,true,true,true,true,true, 8, true,"
            + " now())",
        java.sql.Date.valueOf(screenDate),
        symbol,
        new java.math.BigDecimal("123.45"));
  }

  private int screenRows(LocalDate screenDate, String symbol) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM minervini_screen_results WHERE screen_date = ?::date"
                + " AND symbol = ?",
            Integer.class,
            java.sql.Date.valueOf(screenDate),
            symbol);
    return n == null ? 0 : n;
  }

  private LocalDate tableMaxTradeDate() {
    return jdbc.queryForObject(
        // ⚠️ Deliberately an INLINE literal, not CashEquityUniverse.SERIES_PREDICATE: a test that
        // imports the constant it is checking cannot detect a change to that constant. This is an
        // independent oracle on purpose -- do not "finish" the H24 adoption sweep here.
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series IN ('EQ','BE')", LocalDate.class);
  }

  @Test
  void fixturePreconditionsHold() {
    // This class's rows must define the table's max date, else the asOf-ahead arithmetic below is
    // measuring some other fixture's leakage rather than the behaviour under test.
    assertThat(tableMaxTradeDate())
        .as("this fixture must own the table's max trade date")
        .isEqualTo(DATA_END);
    // And the fixture must actually REACH the stale-row state — an empty table cannot fail Test 1.
    seedStaleScreenRow(DATA_END, STALE);
    assertThat(screenRows(DATA_END, STALE))
        .as("the pre-existing stale row must really be there before the run")
        .isEqualTo(1);
  }

  @Test
  void aPreExistingStaleRowIsRemovedByARecomputation() throws Exception {
    // A row an earlier (pre-guard) run wrote for a symbol that has since stopped printing.
    seedStaleScreenRow(DATA_END, STALE);
    assertThat(screenRows(DATA_END, STALE)).as("precondition: stale row present").isEqualTo(1);

    mockMvc
        .perform(post("/api/v1/market/screener/minervini/run").param("asOf", DATA_END.toString()))
        .andExpect(status().isOk());

    assertThat(screenRows(DATA_END, STALE))
        .as("a recomputation must REMOVE the stale row, not merely decline to re-insert it")
        .isZero();
    assertThat(screenRows(DATA_END, FRESH.get(0)))
        .as("a symbol printing through the screen date is still published")
        .isEqualTo(1);
  }

  @Test
  void anAsOfAheadOfTheDataIsLabelledWithTheRealWatermark() throws Exception {
    LocalDate ahead = DATA_END.plusDays(5); // no bhavcopy exists on or after this

    mockMvc
        .perform(post("/api/v1/market/screener/minervini/run").param("asOf", ahead.toString()))
        .andExpect(status().isOk());

    assertThat(screenRows(ahead, FRESH.get(0)))
        .as("nothing may be persisted under a date the data does not reach")
        .isZero();
    assertThat(screenRows(DATA_END, FRESH.get(0)))
        .as("the run must be labelled with the real bhavcopy watermark")
        .isEqualTo(1);
  }
}
