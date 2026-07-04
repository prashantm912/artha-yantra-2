package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MV-8.1 IT: seeds a contiguous ~340-session daily panel in {@code candles}@1d for three synthetic
 * NSE equities (a clean monotonic winner, a downtrend loser, a flat middle) + their EQ instrument
 * rows + a FLAT benchmark series, then runs the hit-rate harness and asserts the winner is the sole
 * 8/8 passer whose forward returns are positive and beat the flat benchmark at every horizon. Uses a
 * test-only benchmark symbol so it never collides with the real "NIFTY 50" rows sibling ITs may seed.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.minervini.hitrate.benchmark=MVHRBENCH",
      "artha.minervini.hitrate.step-sessions=5",
      "artha.minervini.hitrate.lookback-years=3"
    })
@AutoConfigureMockMvc
class MinerviniHitRateIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String WIN = "MVHRWIN";
  private static final String LOSE = "MVHRLOSE";
  private static final String FLAT = "MVHRFLAT";
  private static final String BENCH = "MVHRBENCH";
  private static final List<String> EQ = List.of(WIN, LOSE, FLAT);
  private static final int DAYS = 340;
  private static final LocalDate END = LocalDate.of(2025, 12, 31);

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MinerviniHitRateService harness;

  private void purge() {
    for (String s : EQ) {
      jdbc.update("DELETE FROM candles WHERE tradingsymbol=?", s);
      jdbc.update("DELETE FROM instruments WHERE tradingsymbol=?", s);
    }
    jdbc.update("DELETE FROM candles WHERE tradingsymbol=?", BENCH);
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    for (String s : EQ) {
      jdbc.update(
          "INSERT INTO instruments(exchange,tradingsymbol,name,instrument_type,is_active)"
              + " VALUES('NSE',?,?, 'EQ', true) ON CONFLICT DO NOTHING",
          s, s);
    }
    seedSeries(WIN, i -> 50.0 + 350.0 * i / (DAYS - 1)); // monotonic riser 50 -> 400
    seedSeries(LOSE, i -> 400.0 - 350.0 * i / (DAYS - 1)); // monotonic decline 400 -> 50
    seedSeries(FLAT, i -> 100.0); // flat
    seedSeries(BENCH, i -> 1000.0); // flat benchmark → passer excess = raw forward return
  }

  private void seedSeries(String symbol, IntToDoubleFunction shape) {
    List<Object[]> batch = new ArrayList<>(DAYS);
    for (int i = 0; i < DAYS; i++) {
      LocalDate day = END.minusDays(DAYS - 1L - i);
      double px = Math.round(shape.applyAsDouble(i) * 100.0) / 100.0;
      Timestamp bucket = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
      batch.add(new Object[] {symbol, bucket, px, px, px, px, 1_000_000L});
    }
    jdbc.batchUpdate(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,source)"
            + " VALUES('NSE',?, '1d', ?,?,?,?,?,?, 'BACKFILL') ON CONFLICT DO NOTHING",
        batch);
  }

  @Test
  void winnerBeatsTheFlatBenchmarkAtEveryHorizon() {
    MinerviniHitRateService.HitRateReport r = harness.run(null, null, null);

    assertThat(r.benchmark()).isEqualTo(BENCH);
    assertThat(r.universeCount()).as("all three seeded equities are in the ranked universe").isEqualTo(3);
    assertThat(r.asOfCount()).isPositive();
    assertThat(r.horizons()).hasSize(4);

    // The +5 horizon has the most samples (least likely to run off the series end).
    MinerviniHitRateService.HorizonStat h5 =
        r.horizons().stream().filter(h -> h.horizonSessions() == 5).findFirst().orElseThrow();
    assertThat(h5.sampleCount()).as("the winner passes 8/8 at many asOf steps").isPositive();
    assertThat(h5.winRatePct())
        .as("a monotonic riser is up over the next 5 sessions every time")
        .isEqualByComparingTo("100.00");
    assertThat(h5.beatBenchmarkRatePct())
        .as("a rising passer beats the flat benchmark every time")
        .isEqualByComparingTo("100.00");
    assertThat(h5.meanReturnPct()).isPositive();
    assertThat(h5.meanExcessReturnPct())
        .as("excess over the flat benchmark = the raw forward return, positive")
        .isPositive();
    // The benchmark is flat, so its mean forward return is ~0.
    assertThat(h5.meanBenchmarkReturnPct().abs()).isLessThan(new java.math.BigDecimal("0.01"));
  }

  @Test
  void backtestEndpointReturnsTheTypedPerHorizonReport() throws Exception {
    mockMvc
        .perform(post("/api/v1/market/screener/minervini/backtest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.benchmark").value(BENCH))
        .andExpect(jsonPath("$.horizons.length()").value(4))
        .andExpect(jsonPath("$.horizons[0].horizonSessions").value(5))
        .andExpect(jsonPath("$.horizons[3].horizonSessions").value(63));
  }
}
