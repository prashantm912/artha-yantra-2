package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * INT increment I1 (intelligence-layer design §6.1 / §6.6): the market-data digest endpoints + the
 * {@code market_context_days} persistence, exercised against the Timescale container + the real
 * deploy/flyway marketdata lineage (V043). Isolation on the shared singleton DB (no per-method
 * cleanup): a UNIQUE underlying per test so {@code reader.latest} anchors on this test's rows.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class MarketContextIntegrationTest extends MarketDataIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired MarketContextDayRepository repository;

  @Test
  void optionsDigestFoldsSeededSessionWithHonestTrust() throws Exception {
    String u = "CTXDIGEST";
    LocalDate exp = LocalDate.of(2026, 6, 25);
    // Session-open bucket (09:16 -> 15m bucket 09:15).
    OffsetDateTime open = OffsetDateTime.of(2026, 6, 24, 9, 16, 0, 0, IST);
    seed(open, u, exp, "22400", "CE", "120", 800L);
    seed(open, u, exp, "22400", "PE", "40", 1500L);
    seed(open, u, exp, "22500", "CE", "60", 1200L);
    seed(open, u, exp, "22500", "PE", "80", 900L);
    // Newest bucket (09:46 -> 15m bucket 09:45): OI rose on every leg (all gainers).
    OffsetDateTime now = OffsetDateTime.of(2026, 6, 24, 9, 46, 0, 0, IST);
    seed(now, u, exp, "22400", "CE", "120", 1000L);
    seed(now, u, exp, "22400", "PE", "40", 2000L);
    seed(now, u, exp, "22500", "CE", "60", 1500L);
    seed(now, u, exp, "22500", "PE", "80", 1000L);

    mockMvc
        .perform(get("/api/v1/market/context/options-digest").param("name", u).param("expiry", "2026-06-25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value(u))
        // Newest bucket PCR = (2000+1000) / (1000+1500) = 1.2000 (OptionsChainService.pcr scale 4).
        .andExpect(jsonPath("$.pcr.now").value("1.2000"))
        // No prior-session baseline + IV history below the 60-session floor -> honest DEGRADED.
        .andExpect(jsonPath("$.dataTrust").value("DEGRADED"))
        .andExpect(jsonPath("$.oiGainers").isNotEmpty())
        .andExpect(jsonPath("$.oiLosers").isEmpty())
        .andExpect(jsonPath("$.oiStructure.verdict").isNotEmpty())
        .andExpect(jsonPath("$.atmStraddle.atmStrike").isNotEmpty());
  }

  @Test
  void optionsDigestIsBlockedNotErrorWhenNoSnapshot() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/context/options-digest")
                .param("name", "CTXNONE")
                .param("expiry", "2026-07-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataTrust").value("BLOCKED"))
        .andExpect(jsonPath("$.oiGainers").isEmpty());
  }

  @Test
  void dayContextAssemblesFailSoftUnderMock() throws Exception {
    // Mock has no live VIX / world-indices / candles -> the block-level reads degrade to notes, never a 5xx.
    mockMvc
        .perform(get("/api/v1/market/context/day-context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionPhase").isNotEmpty())
        .andExpect(jsonPath("$.tradeDate").isNotEmpty())
        .andExpect(jsonPath("$.ingestTrust.overall").isNotEmpty())
        .andExpect(jsonPath("$.ingestTrust.sources").isNotEmpty())
        .andExpect(jsonPath("$.notes").isNotEmpty());
  }

  @Test
  void marketContextDayUpsertIsIdempotentPerSessionDay() {
    LocalDate day = LocalDate.of(2026, 6, 20);
    int first =
        repository.upsert(
            day, "NIFTY", LocalDate.of(2026, 6, 25),
            new BigDecimal("1.10"), new BigDecimal("22500"), new BigDecimal("140.00"),
            new BigDecimal("0.1350"), Map.of("phase", "POST_CLOSE"));
    int second =
        repository.upsert(
            day, "NIFTY", LocalDate.of(2026, 6, 25),
            new BigDecimal("1.20"), new BigDecimal("22500"), new BigDecimal("150.00"),
            new BigDecimal("0.1400"), Map.of("phase", "POST_CLOSE"));

    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(1);
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM market_context_days WHERE trade_date = ?",
            Integer.class,
            java.sql.Date.valueOf(day));
    assertThat(rows).isEqualTo(1);
    BigDecimal pcr =
        jdbc.queryForObject(
            "SELECT pcr_eod FROM market_context_days WHERE trade_date = ?",
            BigDecimal.class,
            java.sql.Date.valueOf(day));
    // The second upsert wins (accumulating ON CONFLICT).
    assertThat(pcr).isEqualByComparingTo("1.20");
  }

  /** Seed one option-chain snapshot leg (spot pinned so the premium/spurt folds resolve an ATM). */
  private void seed(
      OffsetDateTime ts, String u, LocalDate exp, String strike, String type, String ltp, Long oi) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots"
            + " (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, volume, spot_price)"
            + " VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        java.sql.Date.valueOf(exp),
        strike,
        type,
        u + strike + type,
        ltp,
        oi,
        0L,
        null,
        "22480.00");
  }
}
