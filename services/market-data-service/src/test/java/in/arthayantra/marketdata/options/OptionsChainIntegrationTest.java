package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.instruments.InstrumentSyncService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase-15 IT (mock profile): the chain endpoint returns COMPUTED (non-zero) IV/Greeks for liquid
 * strikes, dead wings carry null IV + reason, every persisted row is provenance-complete (exactly
 * recomputable), the history endpoint answers the nearest snapshot, and off-hours degrades to
 * {@code stale: true} with the B-11 zeroed book.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class OptionsChainIntegrationTest extends MarketDataIntegrationTestBase {

  // Mon 2026-06-15 11:00 IST — open session, one day before the fixture ladder's expiry
  private static final Instant OPEN = OffsetDateTime.parse("2026-06-15T11:00:00+05:30").toInstant();
  private static final Instant CLOSED =
      OffsetDateTime.parse("2026-06-15T18:00:00+05:30").toInstant();
  private static final AtomicReference<Instant> NOW = new AtomicReference<>(OPEN);

  @TestConfiguration
  static class MutableClockConfig {
    @Bean
    @Primary
    Clock mutableClock() {
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private InstrumentSyncService syncService;
  @Autowired private OptionsChainService chainService;
  @Autowired private OptionsSnapshotService snapshotService;
  @Autowired private OptionsSnapshotRepository snapshotRepository;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seedAndOpenMarket() {
    NOW.set(OPEN);
    syncService.runSync();
  }

  @Test
  void chainServesComputedIvAndGreeksForLiquidStrikes() throws Exception {
    mockMvc
        .perform(get("/api/v1/market/options/chain").param("underlying", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiry").value("2026-06-16"))
        .andExpect(jsonPath("$.stale").value(false))
        .andExpect(jsonPath("$.pcr").isString())
        .andExpect(jsonPath("$.forwardSource").isString())
        .andExpect(jsonPath("$.spot").isString());

    OptionsChainService.Chain chain = chainService.chain("NIFTY 50", null);
    // the ladder-anchored spot sits mid-ladder, so an ATM region must exist
    OptionsChainService.StrikeRow atm =
        chain.rows().stream()
            .min(
                java.util.Comparator.comparing(
                    r -> r.strike().subtract(chain.spot()).abs()))
            .orElseThrow();
    assertThat(atm.ce().iv()).as("ATM CE IV computed, never zeroed (the v1 defect)").isNotNull();
    assertThat(atm.ce().iv().doubleValue()).isGreaterThan(0.05);
    assertThat(atm.ce().delta().doubleValue()).isBetween(0.0, 1.0);
    assertThat(atm.pe().iv()).isNotNull();
    assertThat(atm.pe().delta().doubleValue()).isBetween(-1.0, 0.0);
    assertThat(atm.ce().priceSource()).isEqualTo("MID");

    // the far wing is zero-quoted by design: null IV + reason, raw quote still present
    OptionsChainService.StrikeRow wing = chain.rows().get(0); // strike 18000
    assertThat(wing.ce().iv()).isNull();
    assertThat(wing.ce().ivReason()).isEqualTo("ZERO_QUOTE");
    assertThat(wing.ce().ltp()).isNotNull();
  }

  /**
   * Every published leg carries a REAL {@code (exchange, tradingsymbol)} master key — the canonical
   * instrument identity (docs/symbol-normalization.md), asserted against {@code instruments} itself
   * rather than a hardcoded "NFO" so it proves PROVENANCE, not a constant. Consumers stamp this pair
   * onto money paths (paper sizing / live order routing), and the alternative they were forced into
   * — guessing the derivatives exchange from the underlying's name — mis-routes any newly listed BSE
   * root (task_032bff42).
   */
  @Test
  void everyChainLegPublishesARealInstrumentMasterKey() {
    OptionsChainService.Chain chain = chainService.chain("NIFTY 50", null);

    List<OptionsChainService.Leg> legs =
        chain.rows().stream()
            .flatMap(row -> java.util.stream.Stream.of(row.ce(), row.pe()))
            .filter(java.util.Objects::nonNull)
            .toList();

    assertThat(legs).as("the fixture ladder resolves legs").isNotEmpty();
    assertThat(legs)
        .allSatisfy(
            leg -> {
              assertThat(leg.exchange()).as("leg %s exchange", leg.tradingsymbol()).isNotBlank();
              assertThat(
                      jdbc.queryForObject(
                          "SELECT count(*) FROM instruments WHERE exchange = ? AND tradingsymbol = ?",
                          Integer.class,
                          leg.exchange(),
                          leg.tradingsymbol()))
                  .as("(%s, %s) is a real instrument-master key", leg.exchange(), leg.tradingsymbol())
                  .isEqualTo(1);
            });
  }

  @Test
  void snapshotPersistsEveryRowProvenanceComplete() {
    OptionsChainService.Chain chain = snapshotService.snapshotNow("NIFTY 50", null);

    OffsetDateTime ts =
        snapshotRepository
            .nearestSnapshotTs("NIFTY 50", chain.expiry(), OffsetDateTime.now(Clock.fixed(NOW.get(), ZoneOffset.UTC)))
            .orElseThrow();
    List<OptionsSnapshotRepository.SnapshotRow> rows =
        snapshotRepository.rowsAt("NIFTY 50", chain.expiry(), ts);

    assertThat(rows).hasSize(962); // 481 strikes × CE+PE — no row ever skipped
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.ltp()).as("raw quote capture unconditional").isNotNull();
              assertThat(row.spotPrice()).isNotNull();
              assertThat(row.forwardPrice()).as("provenance: forward").isNotNull();
              assertThat(row.riskFreeRate()).as("provenance: r").isNotNull();
              assertThat(row.oi()).isNotNull();
            });
    assertThat(rows.stream().filter(r -> r.iv() != null).count())
        .as("liquid strikes carry computed IV")
        .isGreaterThan(100);
    assertThat(
            rows.stream()
                .filter(r -> r.iv() == null)
                .allMatch(r -> r.ivReason() != null))
        .as("every null IV has its reason")
        .isTrue();
    assertThat(rows.stream().filter(r -> "ZERO_QUOTE".equals(r.ivReason())).count())
        .as("the dead wings persist as raw rows")
        .isPositive();

    // the live broadcast key landed with its TTL
    String key = "options.chain.NIFTY 50." + chain.expiry();
    assertThat(redis.opsForValue().get(key)).contains("\"underlying\":\"NIFTY 50\"");
    assertThat(redis.getExpire(key)).isLessThanOrEqualTo(60);
  }

  @Test
  void historyEndpointReturnsTheNearestStoredSnapshot() throws Exception {
    snapshotService.snapshotNow("NIFTY 50", null);

    mockMvc
        .perform(get("/api/v1/market/options/chain/history").param("underlying", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.ts").isString());
  }

  @Test
  void offHoursChainIsStaleWithZeroedBook() {
    NOW.set(CLOSED);

    OptionsChainService.Chain chain = chainService.chain("NIFTY 50", null);

    assertThat(chain.stale()).as("B-11 off-hours degradation").isTrue();
    OptionsChainService.StrikeRow atm =
        chain.rows().stream()
            .min(java.util.Comparator.comparing(r -> r.strike().subtract(chain.spot()).abs()))
            .orElseThrow();
    assertThat(atm.ce().bid()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(atm.ce().iv()).isNull();
    assertThat(atm.ce().ivReason()).isEqualTo("ZERO_QUOTE");
    assertThat(atm.ce().oi()).as("OI freezes to EOD, never vanishes").isNotNull();
  }

  @Test
  void manualSnapshotTriggerIsAccepted202() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/market/options/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"underlying\":\"NIFTY 50\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").isString());
  }

  @Test
  void chainTableHistoryModeServesTheSessionSnapshotChain() throws Exception {
    // F1: History mode must serve the SELECTED session's captured snapshot chain (spot/OI/LTP = that
    // day), NOT today's live black76 chain. Pick the live ATM strike only to reuse a real chain expiry.
    OptionsChainService.Chain chain = chainService.chain("NIFTY 50", null);
    BigDecimal atm =
        chain.rows().stream()
            .min(java.util.Comparator.comparing(r -> r.strike().subtract(chain.spot()).abs()))
            .orElseThrow()
            .strike();
    LocalDate exp = chain.expiry();
    String k = atm.toPlainString();

    // Two 5-min snapshot buckets on an OTHERWISE-UNTOUCHED IST day (2026-06-14); querying the deltas
    // in history mode date-scopes latestPair to this day, isolating it from the shared NIFTY 50 live
    // snapshot history (snapshotNow at 11:00, scheduled captures at the mock clock). CE long buildup
    // (+200 OI, +10 LTP), PE long unwinding (-100 OI, -5 LTP).
    OffsetDateTime b0 = OffsetDateTime.parse("2026-06-14T11:05:00+05:30");
    OffsetDateTime b1 = OffsetDateTime.parse("2026-06-14T11:10:00+05:30");
    insertSnap(b0, exp, k, "CE", "100", 1000L);
    insertSnap(b1, exp, k, "CE", "110", 1200L);
    insertSnap(b0, exp, k, "PE", "90", 1200L);
    insertSnap(b1, exp, k, "PE", "85", 1100L);

    // The captured session (2026-06-14) is date-isolated, so the historical chain has just this ATM
    // strike; its header + legs come from the newest bucket (b1), the deltas from the b0→b1 pair.
    mockMvc
        .perform(
            get("/api/v1/market/options/chain-table")
                .param("name", "NIFTY 50")
                .param("interval", "5m")
                .param("mode", "history")
                .param("date", "2026-06-14"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.interval").value("5m"))
        // F1: spot is the SESSION's captured 23000.00, NOT today's live chain spot (~24000) — proves
        // history date-scoping; asOf is the session's newest bucket.
        .andExpect(jsonPath("$.spot").value(org.hamcrest.Matchers.startsWith("23000")))
        .andExpect(jsonPath("$.asOf").value(org.hamcrest.Matchers.startsWith("2026-06-14")))
        // legs come from the snapshot (b1): CE oi 1200, PE oi 1100 — not live OI.
        .andExpect(jsonPath("$.rows[*].ce.leg.oi").value(org.hamcrest.Matchers.hasItem(1200)))
        .andExpect(jsonPath("$.rows[*].pe.leg.oi").value(org.hamcrest.Matchers.hasItem(1100)))
        // greeks are null on history (the snapshot projection carries IV only, not per-leg greeks).
        .andExpect(
            jsonPath("$.rows[*].ce.leg.delta")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
        // the interval-delta overlay still classifies off the b0→b1 pair.
        .andExpect(
            jsonPath("$.rows[*].ce.deltas.interpretation")
                .value(org.hamcrest.Matchers.hasItem("LONG_BUILDUP")))
        .andExpect(
            jsonPath("$.rows[*].pe.deltas.interpretation")
                .value(org.hamcrest.Matchers.hasItem("LONG_UNWINDING")));
  }

  @Test
  void chainTableFullDayWindowRebasesDeltasToTheSessionOpen() throws Exception {
    // §9.2-3 "Full day": window=cumulative pairs the newest bucket against the SESSION-OPEN bucket
    // (the barometer's default period) instead of the prior bucket. Three buckets on an isolated
    // day: open oi 1000 -> 1500 -> newest 1200. Interval delta = -300; full-day delta = +200.
    OptionsChainService.Chain chain = chainService.chain("NIFTY 50", null);
    BigDecimal atm =
        chain.rows().stream()
            .min(java.util.Comparator.comparing(r -> r.strike().subtract(chain.spot()).abs()))
            .orElseThrow()
            .strike();
    LocalDate exp = chain.expiry();
    String k = atm.toPlainString();
    insertSnap(OffsetDateTime.parse("2026-06-13T09:36:00+05:30"), exp, k, "CE", "100", 1000L);
    insertSnap(OffsetDateTime.parse("2026-06-13T11:06:00+05:30"), exp, k, "CE", "115", 1500L);
    insertSnap(OffsetDateTime.parse("2026-06-13T13:06:00+05:30"), exp, k, "CE", "110", 1200L);

    mockMvc
        .perform(
            get("/api/v1/market/options/chain-table")
                .param("name", "NIFTY 50")
                .param("interval", "5m")
                .param("mode", "history")
                .param("date", "2026-06-13")
                .param("window", "cumulative"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.rows[*].ce.deltas.oiChange").value(org.hamcrest.Matchers.hasItem(200)));

    mockMvc
        .perform(
            get("/api/v1/market/options/chain-table")
                .param("name", "NIFTY 50")
                .param("interval", "5m")
                .param("mode", "history")
                .param("date", "2026-06-13"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.rows[*].ce.deltas.oiChange").value(org.hamcrest.Matchers.hasItem(-300)));
  }

  /** Seeds one option_chain_snapshots row (the chain-table delta overlay needs a captured pair). */
  private void insertSnap(
      OffsetDateTime ts, LocalDate exp, String strike, String type, String ltp, Long oi) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots "
            + "(ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, volume, spot_price) "
            + "VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        "NIFTY 50",
        java.sql.Date.valueOf(exp),
        strike,
        type,
        "NIFTY 50" + strike + type,
        ltp,
        oi,
        0L,
        1000L,
        "23000.00");
  }

  @Test
  void nearestExpiryResolution() {
    assertThat(chainService.resolveExpiry("NIFTY 50", null)).isEqualTo(LocalDate.parse("2026-06-16"));
    assertThat(chainService.resolveExpiry("NIFTY 50", LocalDate.parse("2026-06-16")))
        .isEqualTo(LocalDate.parse("2026-06-16"));
  }
}
