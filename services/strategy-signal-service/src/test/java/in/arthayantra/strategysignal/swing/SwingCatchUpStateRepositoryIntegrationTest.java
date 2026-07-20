package in.arthayantra.strategysignal.swing;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code swing_catchup_runs} atomic claim (V046) against the REAL Timescale + flyway lineage — the
 * durability gate for the catch-up double-fill (2026-07-17 review, Critical 2). Proves the single
 * {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE ... RETURNING} statement is a genuine mutual
 * exclusion: at most one caller wins a fresh session, a fresh RUNNING claim blocks a second, a PENDING
 * row is re-claimable (retry), a terminal DONE/ABANDONED is not, and a STALE RUNNING claim (a crashed
 * attempt) is reclaimable after the lease. Unique batch per method (the singleton DB has no cleanup).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingCatchUpStateRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingCatchUpStateRepository state;
  @Autowired private JdbcTemplate jdbc;

  private static final LocalDate SESSION = LocalDate.of(2026, 7, 17);

  @Test
  void firstClaimWinsAndAConcurrentFreshClaimLoses() {
    String batch = "cu-it-fresh-" + System.nanoTime();

    Optional<SwingCatchUpStateRepository.Claim> first = state.claim(batch, SESSION, 30);
    Optional<SwingCatchUpStateRepository.Claim> second = state.claim(batch, SESSION, 30);

    assertThat(first).isPresent();
    assertThat(first.get().attempts()).isEqualTo(1);
    assertThat(second).as("a fresh RUNNING claim blocks a second caller — no double emission").isEmpty();
  }

  @Test
  void aPendingRowIsReclaimableAndIncrementsAttempts() {
    String batch = "cu-it-pending-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent(); // attempt 1
    state.markPending(batch, SESSION);

    Optional<SwingCatchUpStateRepository.Claim> retry = state.claim(batch, SESSION, 30);

    assertThat(retry).as("a PENDING partial is retryable").isPresent();
    assertThat(retry.get().attempts()).as("attempts accrue across retries").isEqualTo(2);
  }

  @Test
  void aDoneRowIsTerminalAndNotReclaimable() {
    String batch = "cu-it-done-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markDone(batch, SESSION);

    assertThat(state.claim(batch, SESSION, 30)).as("a completed session is never re-run").isEmpty();
  }

  @Test
  void anAbandonedRowIsTerminalAndNotReclaimable() {
    String batch = "cu-it-abandoned-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markAbandoned(batch, SESSION);

    assertThat(state.claim(batch, SESSION, 30)).as("an abandoned session is not chased").isEmpty();
  }

  @Test
  void aDisarmedSessionIsTerminalAndNeverClaimed() {
    // A session the family was intentionally OFF for is recorded DISARMED and must never be recovered.
    String batch = "cu-it-disarmed-" + System.nanoTime();
    state.recordDisarmed(batch, SESSION);

    assertThat(state.claim(batch, SESSION, 30)).as("a DISARMED session is never caught up").isEmpty();
  }

  @Test
  void recordDisarmedNeverOverwritesAnExistingRow() {
    // INSERT-if-absent: a session already being worked (e.g. a PENDING partial) is not clobbered to DISARMED.
    String batch = "cu-it-disarmed-noclobber-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markPending(batch, SESSION);

    state.recordDisarmed(batch, SESSION); // must be a no-op — a real PENDING row exists

    assertThat(state.claim(batch, SESSION, 30)).as("the PENDING survives, still retryable").isPresent();
  }

  @Test
  void aStaleRunningClaimIsReclaimableAfterTheLease() {
    String batch = "cu-it-stale-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent(); // RUNNING, claimed_at now
    // Back-date the claim beyond the lease (a crashed attempt that never released its RUNNING claim).
    jdbc.update(
        "UPDATE swing_catchup_runs SET claimed_at = now() - interval '2 hours'"
            + " WHERE batch = ? AND session_date = ?",
        batch, java.sql.Date.valueOf(SESSION));

    Optional<SwingCatchUpStateRepository.Claim> reclaim = state.claim(batch, SESSION, 30);

    assertThat(reclaim).as("a RUNNING claim staler than the lease is reclaimable").isPresent();
    assertThat(reclaim.get().attempts()).isEqualTo(2);
    // A fresh RUNNING claim right after is again blocked (the reclaim reset claimed_at to now).
    assertThat(state.claim(batch, SESSION, 30)).isEmpty();
  }
}
