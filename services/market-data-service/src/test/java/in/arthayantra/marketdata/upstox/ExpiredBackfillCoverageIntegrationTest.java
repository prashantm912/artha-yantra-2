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
    // hasIncompleteCoverage is a GLOBAL EXISTS(NOT complete AND expiry >= floor) — no per-underlying
    // filter — so the "floor past my row → false" guard only holds while THIS probe is the LATEST
    // incomplete contract in the shared singleton DB. The one other incomplete fixture in the suite
    // (CandleDerivedChainReaderIntegrationTest's DERIV99XCE @ 2099-01-15) leaks in under a different CI
    // test order, so the probe sits at a far-future sentinel PAST it. The query is date-agnostic — the
    // now-1y resume window is the CALLER's floor, so the sentinel year changes nothing under test.
    LocalDate expiry = LocalDate.of(2099, 12, 31);
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
    // Regression guard: floor PAST this row's expiry (and past every other incomplete fixture in the
    // suite) → it is out-of-window/unreachable, so the probe must not count it.
    assertThat(repo.hasIncompleteCoverage(expiry.plusDays(1))).isFalse();
  }
}
