package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-5 IT (MV-5.4/5.5): seeds a synthetic VCP price series into {@code nse_eod_bhavcopy} and
 * asserts (a) the analyzer endpoint reproduces the Technical Footprint + buy pivot end-to-end
 * (reader → detector → controller JSON), (b) the geometry service persists a real {@code is_vcp=true}
 * row into {@code minervini_setups} for a passer, and (c) the scheduler's {@code runOnce} runs the
 * screen end-to-end. Pure SQL, no gateway ports. Shares the singleton DB → purge before AND after so
 * seeded rows never leak. Hermetic by construction: the geometry assertions never depend on the
 * cross-sectional RS-rank (which is shared-DB-universe-sensitive) — they pass an explicit passer.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class MinerviniGeometryIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String VCP = "MVVCP"; // a VCP base (156 bars — below the screen's 252 floor)
  private static final String UP = "MVGUP"; // a clean 260-bar uptrend (always scanned)
  private static final List<String> SYMS = List.of(VCP, UP);
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MinerviniScheduler scheduler;
  @Autowired private MinerviniGeometryService geometry;
  @Autowired private MinerviniSetupsRepository setupsRepo;

  private void purge() {
    for (String s : SYMS) {
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
    seedVcp();
    // A clean 260-bar uptrend 100→200 (always survives the 252-session scan).
    List<Object[]> up = new ArrayList<>(260);
    for (int i = 0; i < 260; i++) {
      LocalDate day = AS_OF.minusDays(260L - 1 - i);
      double px = Math.round((100.0 + 100.0 * i / 259.0) * 100.0) / 100.0;
      up.add(new Object[] {day, UP, px, px, px, 100_000L});
    }
    insert(up);
  }

  @Test
  void analyzerEndpointReproducesFootprintAndPivot() throws Exception {
    // 3-contraction VCP: P1(100)→T1(70)=30%, P2(98)→T2(83.3)=15%, P3(96)→T3(90.24)=6%.
    // P1 at bar 5, pivot P3 at bar 145 → 140 sessions → 28 weeks. Footprint "28W 30/6 3T", pivot 96.
    mockMvc
        .perform(get("/api/v1/market/screener/minervini/candidate/{s}", VCP).param("asOf", AS_OF.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value(VCP))
        .andExpect(jsonPath("$.scanned").value(false)) // below the 252-session screen floor
        .andExpect(jsonPath("$.geometry.isVcp").value(true))
        .andExpect(jsonPath("$.geometry.footprint").value("28W 30/6 3T"))
        .andExpect(jsonPath("$.geometry.contractionCount").value(3))
        // the buy pivot (high of the final tightest contraction) — the trade-critical output
        .andExpect(jsonPath("$.geometry.pivot", Matchers.oneOf("96.0", "96")));
  }

  @Test
  void persistForPassersWritesRealVcpRow() {
    // Positively verify the persist path (detectAll → upsert → find) with an is_vcp=TRUE row.
    // Pass an explicit passer for the VCP symbol so the assertion never depends on the RS-rank
    // universe (which is shared-DB-sensitive).
    int written = geometry.persistForPassers(AS_OF, List.of(passer(VCP)));
    assertThat(written).isEqualTo(1);

    VcpFootprint fp = setupsRepo.find(AS_OF, VCP);
    assertThat(fp).isNotNull();
    assertThat(fp.vcp()).isTrue();
    assertThat(fp.footprint()).isEqualTo("28W 30/6 3T");
    assertThat(fp.contractionCount()).isEqualTo(3);
    assertThat(fp.pivot()).isEqualTo(96.0);
  }

  @Test
  void runOnceRunsTheScreenEndToEnd() {
    scheduler.runOnce(AS_OF);
    // The screen persists EVERY scanned candidate (not just passers), so the uptrend's row is always
    // written — a hermetic proof the scheduler ran the screen without depending on the RS universe.
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM minervini_screen_results WHERE screen_date=? AND symbol=?",
            Integer.class,
            java.sql.Date.valueOf(AS_OF),
            UP);
    assertThat(rows).isEqualTo(1);
  }

  /** A minimal passer TrendCandidate (only symbol + passesAll are read by persistForPassers). */
  private static TrendCandidate passer(String symbol) {
    boolean[] gates = {true, true, true, true, true, true, true, true};
    return new TrendCandidate(
        symbol, "NSE", null, null, null, null, null, null, null, null, null, null, null, gates, 8,
        true, 2, null, null);
  }

  private void seedVcp() {
    int[] idx = {0, 5, 40, 75, 110, 145, 155}; // waypoint bar indices
    double[] px = {70, 100, 70, 98, 83.3, 96, 90.24};
    List<Object[]> rows = new ArrayList<>();
    for (int seg = 0; seg + 1 < idx.length; seg++) {
      int from = idx[seg];
      int to = idx[seg + 1];
      for (int i = (seg == 0 ? 0 : from + 1); i <= to; i++) {
        double p = px[seg] + (px[seg + 1] - px[seg]) * ((double) (i - from) / (to - from));
        double rounded = Math.round(p * 100.0) / 100.0;
        long vol = i <= 145 ? 1_000_000L : (i == 150 ? 80_000L : 250_000L);
        LocalDate day = AS_OF.minusDays(155L - i);
        rows.add(new Object[] {day, VCP, rounded, rounded, rounded, vol});
      }
    }
    insert(rows);
  }

  private void insert(List<Object[]> rows) {
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,close_price,high_price,low_price,"
            + "ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?) ON CONFLICT DO NOTHING",
        rows);
  }
}
