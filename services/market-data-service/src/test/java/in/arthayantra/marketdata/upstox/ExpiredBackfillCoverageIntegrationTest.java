package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * IT for {@link ExpiredBackfillRepository#hasIncompleteCoverage()} — the lock-safe "work remains"
 * probe the auto-resume self-heal reads. Registering one {@code complete = false} contract makes the
 * registry EXISTS-probe return true. (The shared singleton DB has no per-method cleanup, so the
 * positive case is asserted monotonically — an incomplete row is enough; the false branch is covered
 * by {@code ExpiredBackfillAutoResumeTest}.)
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
  void incompleteContractMakesCoverageProbeTrue() {
    repo.upsertContract(
        "BFO",
        "SENSEX-AUTORESUME-IT-PROBE",
        "PE",
        "SENSEX",
        LocalDate.of(2025, 7, 1),
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

    assertThat(repo.hasIncompleteCoverage()).isTrue();
  }
}
