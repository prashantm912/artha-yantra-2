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

/** Pins the V047 atomic detector episode latch against the real Timescale + Flyway schema. */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingMissedBatchAlertRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingMissedBatchAlertRepository state;
  @Autowired private JdbcTemplate jdbc;

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 17);

  @Test
  void firstClaimWinsAndAConcurrentFreshClaimLoses() {
    String batch = "cu-it-fresh-" + UUID.randomUUID();

    Optional<SwingMissedBatchAlertRepository.Claim> first = state.claim(batch, SESSION, 30);
    Optional<SwingMissedBatchAlertRepository.Claim> second = state.claim(batch, SESSION, 30);

    assertThat(first).isPresent();
    assertThat(first.get().attempts()).isEqualTo(1);
    assertThat(second).as("a fresh RUNNING claim blocks a second page").isEmpty();
  }

  @Test
  void anAbandonedRowIsTerminalAndNotReclaimed() {
    String batch = "cu-it-abandoned-" + UUID.randomUUID();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markAbandoned(batch, SESSION, "MISSED_BATCH_ALERTED");

    assertThat(state.claim(batch, SESSION, 30)).as("a latched episode is not re-paged").isEmpty();
  }

  @Test
  void aStaleRunningClaimIsReclaimableAfterTheLease() {
    String batch = "cu-it-stale-" + UUID.randomUUID();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    jdbc.update(
        "UPDATE swing_missed_batch_alerts SET claimed_at = now() - interval '2 hours'"
            + " WHERE batch = ? AND session_date = ?",
        batch, java.sql.Date.valueOf(SESSION));

    Optional<SwingMissedBatchAlertRepository.Claim> reclaim = state.claim(batch, SESSION, 30);

    assertThat(reclaim).isPresent();
    assertThat(reclaim.get().attempts()).isEqualTo(2);
    assertThat(state.claim(batch, SESSION, 30)).isEmpty();
  }

  @Test
  void abandonedStatePersistsTheReason() {
    String batch = "cu-it-abandon-reason-" + UUID.randomUUID();
    state.markAbandoned(batch, SESSION, "MISSED_BATCH_ALERTED");

    String reason =
        jdbc.queryForObject(
            "SELECT reason FROM swing_missed_batch_alerts WHERE batch = ? AND session_date = ?",
            String.class,
            batch,
            java.sql.Date.valueOf(SESSION));
    assertThat(reason).isEqualTo("MISSED_BATCH_ALERTED");
  }
}
