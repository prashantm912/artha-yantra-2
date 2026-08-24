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
    LocalDate windowFrom = LocalDate.parse("2026-06-10");
    LocalDate windowTo = LocalDate.parse("2026-06-11");

    // ⚠️ SEED THE CONTAMINANT DELIBERATELY, through the real accrual path. On the shared singleton
    // IT DB (no per-method cleanup, the #405 rule) a sibling context accrues a snapshot for this
    // same index at ITS clock's date, and a sibling clock can be in the PAST — this class's window
    // is not special. Reproducing that here makes the isolation deterministic rather than dependent
    // on which classes surefire happens to run first. Safe to persist: nothing else in the suite
    // reads IndexConstituentsRepository#snapshotDates, and the accrual is append-only history, so a
    // dated row below both sync dates cannot change "latest" or any ?asOf= assertion.
    MutableClockConfig.NOW.set(Instant.parse("2026-03-02T03:30:00Z"));
    accrualStep.afterStaging();

    MutableClockConfig.NOW.set(Instant.parse("2026-06-10T03:30:00Z"));
    accrualStep.afterStaging();
    accrualStep.afterStaging(); // same date again — append-only no-op

    MutableClockConfig.NOW.set(Instant.parse("2026-06-11T03:30:00Z"));
    accrualStep.afterStaging();

    // The filter is a WINDOW, bounded on BOTH sides. It used to bound only the upper edge, which
    // defended against a sibling's TODAY-dated snapshot and nothing else — so CI saw
    // [2026-03-02, 2026-06-10, 2026-06-11] and failed on a row this test never wrote. A one-sided
    // guard against cross-test bleed is half a guard: contamination has no preferred direction.
    // Inside the window, exactness still proves the same-date re-run was an append-only no-op
    // (exactly two rows, not three).
    assertThat(repository.snapshotDates("NIFTY 100"))
        .filteredOn(d -> !d.isBefore(windowFrom) && !d.isAfter(windowTo))
        .containsExactly(windowFrom, windowTo);
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
    // "Latest" is collision-prone on the shared DB (a leaked TODAY snapshot outranks 2026-06-11),
    // so assert latest >= our newest seeded date rather than pinning the exact day; the mock
    // fixture is the only membership source either way, so the 50-row shape holds.
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.get("/api/v1/instruments/indices/NIFTY 100/constituents"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.items.length()").value(50))
            .andExpect(MockMvcResultMatchers.jsonPath("$.checksum").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String latestAsOf =
        com.jayway.jsonpath.JsonPath.read(body, "$.asOf");
    assertThat(LocalDate.parse(latestAsOf))
        .isAfterOrEqualTo(LocalDate.parse("2026-06-11"));

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
