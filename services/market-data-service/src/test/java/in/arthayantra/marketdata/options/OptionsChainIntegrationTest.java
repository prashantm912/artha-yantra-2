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
  void nearestExpiryResolution() {
    assertThat(chainService.resolveExpiry("NIFTY 50", null)).isEqualTo(LocalDate.parse("2026-06-16"));
    assertThat(chainService.resolveExpiry("NIFTY 50", LocalDate.parse("2026-06-16")))
        .isEqualTo(LocalDate.parse("2026-06-16"));
  }
}
