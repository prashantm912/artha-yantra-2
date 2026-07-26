package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Pins the V047 page lease against the real Timescale + Flyway schema. */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingMissedBatchAlertRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingMissedBatchAlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 17);

  @Test
  void theFirstClaimWinsAndASecondInsideTheLeaseLoses() {
    String batch = "alert-it-fresh-" + UUID.randomUUID();

    Optional<SwingMissedBatchAlertRepository.Claim> first = alerts.claim(batch, SESSION, 30);
    Optional<SwingMissedBatchAlertRepository.Claim> second = alerts.claim(batch, SESSION, 30);

    assertThat(first).isPresent();
    assertThat(first.get().pages()).isEqualTo(1);
    assertThat(second).as("a page inside the lease window is suppressed").isEmpty();
  }

  /**
   * The property that makes this a rate limiter rather than a one-shot latch: an unresolved session
   * must page AGAIN once the lease expires, because the previous page was only ever enqueued, never
   * confirmed delivered.
   */
  @Test
  void anExpiredLeaseRepagesTheSameSession() {
    String batch = "alert-it-repage-" + UUID.randomUUID();
    assertThat(alerts.claim(batch, SESSION, 30)).isPresent();
    expireLease(batch);

    Optional<SwingMissedBatchAlertRepository.Claim> repage = alerts.claim(batch, SESSION, 30);

    assertThat(repage).as("the session pages again after its lease expires").isPresent();
    assertThat(repage.get().pages()).as("and counts how many mornings it has paged").isEqualTo(2);
    assertThat(alerts.claim(batch, SESSION, 30)).as("the new lease then holds").isEmpty();
  }

  @Test
  void thePageCountKeepsClimbingAcrossSuccessiveLeases() {
    String batch = "alert-it-count-" + UUID.randomUUID();
    for (int expected = 1; expected <= 3; expected++) {
      Optional<SwingMissedBatchAlertRepository.Claim> claim = alerts.claim(batch, SESSION, 30);
      assertThat(claim).isPresent();
      assertThat(claim.get().pages()).isEqualTo(expected);
      expireLease(batch);
    }
  }

  private void expireLease(String batch) {
    jdbc.update(
        "UPDATE swing_missed_batch_alerts SET claimed_at = now() - interval '2 hours'"
            + " WHERE batch = ? AND session_date = ?",
        batch, java.sql.Date.valueOf(SESSION));
  }
}
