package in.arthayantra.marketdata.constituents;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Phase 22 IT (S8): two syncs on different mock dates accrue TWO immutable snapshots; the
 * endpoint serves latest by default and an exact date with {@code ?asOf=}; checksums are
 * deterministic for identical lists; re-running on the same date never mutates prior rows.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IndexConstituentsIntegrationTest extends MarketDataIntegrationTestBase {

  /** A mutable test clock so two "days" of accrual run in one suite. */
  @TestConfiguration
  static class MutableClockConfig {
    static final AtomicReference<Instant> NOW =
        new AtomicReference<>(Instant.parse("2026-06-10T03:30:00Z")); // 09:00 IST

    @Bean
    @org.springframework.context.annotation.Primary
    Clock mutableClock() {
      return new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return Clock.fixed(NOW.get(), zone);
        }

        @Override
        public Instant instant() {
          return NOW.get();
        }
      };
    }
  }

  @Autowired private IndexConstituentsAccrualStep accrualStep;
  @Autowired private IndexConstituentsRepository repository;
  @Autowired private MockIndexConstituentsFetcher fetcher;
  @Autowired private MockMvc mockMvc;

  @Test
  @Order(1)
  void twoSyncDatesAccrueTwoImmutableSnapshots() {
    MutableClockConfig.NOW.set(Instant.parse("2026-06-10T03:30:00Z"));
    accrualStep.afterStaging();
    accrualStep.afterStaging(); // same date again — append-only no-op

    MutableClockConfig.NOW.set(Instant.parse("2026-06-11T03:30:00Z"));
    accrualStep.afterStaging();

    assertThat(repository.snapshotDates("NIFTY 100"))
        .containsExactly(LocalDate.parse("2026-06-10"), LocalDate.parse("2026-06-11"));
    List<IndexConstituentsFetcher.Constituent> day1 =
        repository.membership("NIFTY 100", LocalDate.parse("2026-06-10"));
    List<IndexConstituentsFetcher.Constituent> day2 =
        repository.membership("NIFTY 100", LocalDate.parse("2026-06-11"));
    assertThat(day1).hasSize(50).isEqualTo(day2);
    assertThat(day1).contains(new IndexConstituentsFetcher.Constituent("NSE", "RELIANCE"));
  }

  @Test
  @Order(2)
  void checksumIsDeterministicForIdenticalLists() {
    List<IndexConstituentsFetcher.Constituent> day1 =
        repository.membership("NIFTY 100", LocalDate.parse("2026-06-10"));
    List<IndexConstituentsFetcher.Constituent> day2 =
        repository.membership("NIFTY 100", LocalDate.parse("2026-06-11"));

    assertThat(IndexConstituentsRepository.checksum(day1))
        .isEqualTo(IndexConstituentsRepository.checksum(day2))
        .hasSize(64);
  }

  @Test
  @Order(3)
  void endpointServesLatestByDefaultAndExactDateWithAsOf() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/instruments/indices/NIFTY 100/constituents"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.asOf").value("2026-06-11"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.items.length()").value(50))
        .andExpect(MockMvcResultMatchers.jsonPath("$.checksum").isString());

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/instruments/indices/NIFTY 100/constituents")
                .param("asOf", "2026-06-10"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.asOf").value("2026-06-10"));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/instruments/indices/UNKNOWN IDX/constituents"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  @Order(4)
  void fixtureSymbolsResolveInTheMockMaster() {
    // the fixture must stay consistent with the CD-14 mock dump (universe resolution depends on it)
    List<IndexConstituentsFetcher.Constituent> fixture =
        fetcher.fetch("NIFTY 100").orElseThrow();
    assertThat(fixture).hasSize(50);
    assertThat(fixture)
        .allSatisfy(c -> assertThat(c.exchange()).isEqualTo("NSE"));
    assertThat(fetcher.fetch("NIFTY MIDCAP"))
        .as("unknown indices report empty, never a fake list")
        .isEmpty();
  }
}
