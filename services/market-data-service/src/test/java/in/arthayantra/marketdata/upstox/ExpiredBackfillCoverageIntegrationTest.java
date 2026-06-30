package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * IT for {@link ExpiredBackfillRepository#hasIncompleteCoverage(LocalDate)} — the lock-safe "work
 * remains" probe the auto-resume self-heal reads. Registering one {@code complete = false} contract
 * makes the registry EXISTS-probe return true when its expiry is at/after the floor, and (the
 * regression guard) NOT true when the floor is past it — an out-of-window partial is unreachable and
 * must not count. (The shared singleton DB has no per-method cleanup, so the window assertions are
 * relative to THIS row's own expiry, never a global empty/non-empty claim.)
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ExpiredBackfillCoverageIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private ExpiredBackfillRepository repo;

  @Test
  void incompleteContractCountsOnlyInsideTheResumeWindow() {
    LocalDate expiry = LocalDate.of(2025, 7, 1);
    repo.upsertContract(
        "BFO",
        "SENSEX-AUTORESUME-IT-PROBE",
        "PE",
        "SENSEX",
        expiry,
        new BigDecimal("80000"),
        10,
        new BigDecimal("0.05"),
        true,
        "BFO|SENSEX-AUTORESUME-IT-PROBE",
        "BSE_INDEX|SENSEX",
        null,
        null,
        0,
        false);

    // Floor at/before this row's expiry → it is reachable work → probe true.
    assertThat(repo.hasIncompleteCoverage(expiry)).isTrue();
    assertThat(repo.hasIncompleteCoverage(expiry.minusYears(1))).isTrue();
    // Regression guard: floor PAST this row's expiry → it is out-of-window/unreachable. The probe
    // must not count it. (Monotonic-safe: this floor is one day past THIS row, and no other test
    // inserts an incomplete contract dated this far in the future.)
    assertThat(repo.hasIncompleteCoverage(expiry.plusDays(1))).isFalse();
  }
}
