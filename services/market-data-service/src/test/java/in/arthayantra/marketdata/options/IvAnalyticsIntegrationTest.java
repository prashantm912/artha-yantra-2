package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.options.IvDailySummaryRepository.Summary;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-42B IT (mock profile): the 16:00 rollup distils seeded {@code options_chain_snapshots} into
 * deterministic {@code iv_daily_summary} rows (recompute reproduces identical rows, bumping only
 * {@code computed_at}); the iv-history endpoint serves the trailing series with the honest
 * insufficient-history state below the floor. ITs share the singleton DB — each method uses a
 * unique underlying for isolation.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class IvAnalyticsIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private IvAnalyticsService analytics;
  @Autowired private IvDailySummaryRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void recomputeIsDeterministicAndIdempotent() {
    String underlying = "IVTEST-" + UUID.randomUUID();
    LocalDate expiry = LocalDate.parse("2026-08-15");
    for (LocalDate day :
        List.of(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-02"), LocalDate.parse("2026-06-03"))) {
      // an earlier pass with a decoy ATM IV — the rollup must use the LAST pass of the day
      seed(underlying, day.atTime(15, 50), expiry, "18000", "CE", "0.990000", "18000");
      // the 15:55 rollup pass: ATM 18000 (CE 0.16 / PE 0.14 -> 0.15) plus a wing
      seed(underlying, day.atTime(15, 55), expiry, "18000", "CE", "0.160000", "18000");
      seed(underlying, day.atTime(15, 55), expiry, "18000", "PE", "0.140000", "18000");
      seed(underlying, day.atTime(15, 55), expiry, "18100", "CE", "0.180000", "18000");
    }

    assertThat(analytics.recompute(underlying, null, null)).isEqualTo(3);
    List<Summary> first = repository.series(underlying);
    assertThat(first).hasSize(3);
    assertThat(first.get(0).atmIv()).isEqualByComparingTo("0.150000"); // last pass wins, not 0.99
    assertThat(first.get(0).iv30d()).isNull(); // single expiry → no 30d bracket
    assertThat(first.get(0).snapshotCount()).isEqualTo(2); // two distinct ts that day
    OffsetDateTime firstComputedAt = first.get(0).computedAt();

    assertThat(analytics.recompute(underlying, null, null)).isEqualTo(3);
    List<Summary> second = repository.series(underlying);
    assertThat(second).hasSize(3); // idempotent — no duplicate rows
    assertThat(second.get(0).atmIv()).isEqualByComparingTo("0.150000");
    assertThat(second.get(0).computedAt()).isAfterOrEqualTo(firstComputedAt); // only computed_at moves
  }

  @Test
  void ivHistoryEndpointSurfacesTheInsufficientHistoryStateBelowTheFloor() throws Exception {
    String underlying = "IVTEST-" + UUID.randomUUID();
    LocalDate expiry = LocalDate.parse("2026-08-15");
    for (LocalDate day :
        List.of(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-02"), LocalDate.parse("2026-06-03"))) {
      seed(underlying, day.atTime(15, 55), expiry, "18000", "CE", "0.160000", "18000");
      seed(underlying, day.atTime(15, 55), expiry, "18000", "PE", "0.140000", "18000");
    }
    analytics.recompute(underlying, null, null);

    mockMvc
        .perform(get("/api/v1/market/options/iv-history").param("underlying", underlying))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowDays").value(3))
        .andExpect(jsonPath("$.floorDays").value(60))
        .andExpect(jsonPath("$.insufficientHistory").value(true))
        .andExpect(jsonPath("$.rank").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.currentIv").isString())
        .andExpect(jsonPath("$.series.length()").value(3));
  }

  @Test
  void thirtyDayConstantMaturityInterpolatesAcrossTwoExpiries() {
    String underlying = "IVTEST-" + UUID.randomUUID();
    LocalDate day = LocalDate.parse("2026-06-10");
    LocalDate near = day.plusDays(20);
    LocalDate far = day.plusDays(45);
    seed(underlying, day.atTime(15, 55), near, "18000", "CE", "0.200000", "18000");
    seed(underlying, day.atTime(15, 55), near, "18000", "PE", "0.200000", "18000");
    seed(underlying, day.atTime(15, 55), far, "18000", "CE", "0.160000", "18000");
    seed(underlying, day.atTime(15, 55), far, "18000", "PE", "0.160000", "18000");

    analytics.recompute(underlying, null, null);
    Summary summary = repository.series(underlying).get(0);
    assertThat(summary.atmIv()).isEqualByComparingTo("0.200000"); // nearest expiry
    assertThat(summary.iv30d()).isNotNull();
    assertThat(summary.iv30d().doubleValue()).isBetween(0.16, 0.20);
  }

  private void seed(
      String underlying,
      java.time.LocalDateTime istLocal,
      LocalDate expiry,
      String strike,
      String type,
      String iv,
      String spot) {
    OffsetDateTime ts = istLocal.atOffset(in.arthayantra.common.web.time.Ist.OFFSET);
    jdbc.update(
        """
        INSERT INTO options_chain_snapshots
          (ts, underlying, expiry, strike, option_type, tradingsymbol, spot_price, iv)
        VALUES (?,?,?,?,?,?,?,?)
        ON CONFLICT DO NOTHING
        """,
        Timestamp.from(ts.toInstant()),
        underlying,
        java.sql.Date.valueOf(expiry),
        new BigDecimal(strike),
        type,
        underlying + type + strike,
        new BigDecimal(spot),
        new BigDecimal(iv));
  }
}
