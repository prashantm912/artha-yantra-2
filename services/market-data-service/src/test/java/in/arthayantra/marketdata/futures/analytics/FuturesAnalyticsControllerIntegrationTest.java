package in.arthayantra.marketdata.futures.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class FuturesAnalyticsControllerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void oiAnalysisReturnsItemsEnvelope() throws Exception {
    String u = "FUTCTRL";
    OffsetDateTime t0 =
        OffsetDateTime.of(2026, 6, 20, 9, 16, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    jdbc.update(
        "INSERT INTO futures_oi_snapshots (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(t0.toInstant()),
        u,
        u + "26JUNFUT",
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)),
        "100.00",
        10L,
        5000L,
        200L);

    mockMvc
        .perform(get("/api/v1/market/futures/oi-analysis").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value(u + "26JUNFUT"));
  }

  private void insert(
      OffsetDateTime ts,
      String u,
      String sym,
      LocalDate exp,
      String ltp,
      long oi,
      String prevClose) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change, prev_close) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        sym,
        java.sql.Date.valueOf(exp),
        ltp,
        10L,
        oi,
        0L,
        prevClose);
  }

  @Test
  void spurtReturnsInterpretation() throws Exception {
    String u = "FUTSPURTCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    insert(b0, u, u + "26JUNFUT", exp, "100", 1000L, "100");
    insert(b1, u, u + "26JUNFUT", exp, "110", 1200L, "100");

    mockMvc
        .perform(get("/api/v1/market/futures/spurt").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.items[0].oiChange").value(200))
        .andExpect(jsonPath("$.items[0].spurtPct").value("20.00"));
  }

  @Test
  void moversSplitGainersAndLosers() throws Exception {
    String u = "FUTMOVCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    insert(b0, u, "AFUT", exp, "100", 1000L, "100");
    insert(b1, u, "AFUT", exp, "110", 1200L, "100"); // +10%
    insert(b0, u, "BFUT", exp, "200", 500L, "200");
    insert(b1, u, "BFUT", exp, "180", 600L, "200"); // -10%

    mockMvc
        .perform(get("/api/v1/market/futures/movers").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.gainers[0].tradingsymbol").value("AFUT"))
        .andExpect(jsonPath("$.losers[0].tradingsymbol").value("BFUT"));
  }

  private void insertOhlc(
      OffsetDateTime ts,
      String u,
      String sym,
      String ltp,
      long oi,
      String dayOpen,
      String dayHigh,
      String dayLow) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots "
            + "(ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change, "
            + " day_open, day_high, day_low) "
            + "VALUES (?,?,?,?,?::numeric,?,?,?,?::numeric,?::numeric,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        sym,
        java.sql.Date.valueOf(LocalDate.of(2026, 6, 25)),
        ltp,
        100L,
        oi,
        50L,
        dayOpen,
        dayHigh,
        dayLow);
  }

  @Test
  void buzzReturnsMatrix() throws Exception {
    String u = "FUTBUZZCTRL";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    insert(b0, u, u + "26JUNFUT", exp, "100", 1000L, "100");
    insert(b1, u, u + "26JUNFUT", exp, "110", 1200L, "100");

    mockMvc
        .perform(get("/api/v1/market/futures/buzz").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contracts[0]").value(u + "26JUNFUT"))
        .andExpect(jsonPath("$.cells[1][0]").value("LONG_BUILDUP"));
  }

  @Test
  void banksGridReturnsFrontContractRowPerUnderlyingSortedByOi() throws Exception {
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    LocalDate jun = LocalDate.of(2026, 6, 25);
    LocalDate jul = LocalDate.of(2026, 7, 30);
    // HDFCBANK + SBIN are in the default artha.futures.bank-stocks universe.
    insert(b0, "HDFCBANK", "HDFCBANK26JUNFUT", jun, "100", 1000L, "100");
    insert(b1, "HDFCBANK", "HDFCBANK26JUNFUT", jun, "110", 1200L, "100");
    insert(b0, "HDFCBANK", "HDFCBANK26JULFUT", jul, "105", 50L, "105"); // far — ignored
    insert(b1, "HDFCBANK", "HDFCBANK26JULFUT", jul, "106", 60L, "105");
    insert(b0, "SBIN", "SBIN26JUNFUT", jun, "200", 5000L, "200");
    insert(b1, "SBIN", "SBIN26JUNFUT", jun, "198", 4800L, "200");

    mockMvc
        .perform(get("/api/v1/market/futures/banks-grid").param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].underlying").value("SBIN")) // oi desc
        .andExpect(jsonPath("$.items[1].underlying").value("HDFCBANK"))
        .andExpect(jsonPath("$.items[1].tradingsymbol").value("HDFCBANK26JUNFUT"))
        .andExpect(jsonPath("$.items[1].pricePct").value("10.00"))
        .andExpect(jsonPath("$.items[1].interpretation").value("LONG_BUILDUP"));
  }

  @Test
  void banksGridHistoryModeWithNoDataReturns422() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/futures/banks-grid")
                .param("mode", "history")
                .param("date", "2000-01-03")
                .param("interval", "5m"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void eodRollsUpDailyOhlc() throws Exception {
    String u = "FUTEODCTRL";
    OffsetDateTime t1 =
        OffsetDateTime.of(2026, 6, 18, 9, 30, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime t2 =
        OffsetDateTime.of(2026, 6, 18, 15, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    insertOhlc(t1, u, u + "26JUNFUT", "100", 1000L, "99", "101", "98");
    insertOhlc(t2, u, u + "26JUNFUT", "105", 1100L, "99", "106", "97");

    mockMvc
        .perform(get("/api/v1/market/futures/eod").param("name", u).param("from", "2026-06-18"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].oiClose").value(1100))
        .andExpect(jsonPath("$.items[0].high").value(org.hamcrest.Matchers.startsWith("106")));
  }

  @Test
  void banksOrderByExpiry() throws Exception {
    String u = "FUTBANKCTRL";
    OffsetDateTime b0 =
        OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    OffsetDateTime b1 = b0.plusMinutes(5);
    insert(b0, u, "BANKJUN", LocalDate.of(2026, 6, 25), "99", 1900L, "99");
    insert(b1, u, "BANKJUN", LocalDate.of(2026, 6, 25), "100", 2000L, "99");
    insert(b0, u, "BANKJUL", LocalDate.of(2026, 7, 30), "128", 900L, "128");
    insert(b1, u, "BANKJUL", LocalDate.of(2026, 7, 30), "130", 1000L, "128");

    mockMvc
        .perform(get("/api/v1/market/futures/banks").param("name", u).param("interval", "5m"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value("BANKJUN")) // expiry-ordered
        .andExpect(jsonPath("$.items[1].tradingsymbol").value("BANKJUL"));
  }
}
