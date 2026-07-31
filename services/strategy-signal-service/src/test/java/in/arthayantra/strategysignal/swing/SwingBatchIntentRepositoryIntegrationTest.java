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

/** Pins the schedule-time arming ledger against the real V047 strategy schema. */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingBatchIntentRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingBatchIntentRepository intents;
  @Autowired private SwingMissedBatchAlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void scheduleIntentIsImmutableForOneSessionAndMissingRowsAreEmpty() {
    String batch = "intent-it-" + UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 7, 17);

    intents.recordScheduled(batch, session, true);
    intents.recordScheduled(batch, session, false);

    assertThat(intents.find(batch, session)).contains(true);
    assertThat(intents.find(batch, session.plusDays(1))).isEqualTo(Optional.empty());
  }

  /**
   * The container-down lookup: a session the scheduler never fired for has no row of its own, so the
   * detector resolves its arming from the newest intent at or before it. A batch with no history at
   * all stays empty, which is what keeps a fresh deploy silent.
   */
  @Test
  void lastKnownArmingCarriesForwardToSessionsWithNoRowOfTheirOwn() {
    String batch = "intent-it-lastknown-" + UUID.randomUUID();
    LocalDate armedOn = LocalDate.of(2026, 7, 14);

    assertThat(intents.lastKnownArmedOnOrBefore(batch, armedOn))
        .as("no intent history anywhere")
        .isEmpty();

    intents.recordScheduled(batch, armedOn, true);

    assertThat(intents.lastKnownArmedOnOrBefore(batch, armedOn.plusDays(3))).contains(true);
    assertThat(intents.lastKnownArmedOnOrBefore(batch, armedOn.minusDays(1)))
        .as("never reads intent from the future")
        .isEmpty();

    intents.recordScheduled(batch, armedOn.plusDays(1), false);

    assertThat(intents.lastKnownArmedOnOrBefore(batch, armedOn.plusDays(3)))
        .as("the newest intent at or before the session wins")
        .contains(false);
  }

  @Test
  void scheduleIntentCapturesDisabledState() {
    String batch = "intent-it-disabled-" + UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 7, 20);

    intents.recordScheduled(batch, session, false);

    assertThat(intents.find(batch, session)).contains(false);
    assertThat(intents.claimableMissedSessionsBefore(batch, session.plusDays(1), 30, 5, 64))
        .isEmpty();
  }

  /**
   * The scan mirrors the page ceiling that {@code claim()} enforces atomically. That mirror is
   * defence in depth, so a mistake in it would be invisible in behaviour — {@code claim()} would
   * still refuse — which is exactly why it needs its own test rather than riding on the repository's.
   */
  @Test
  void theScanStopsOfferingASessionOnceItHitsThePageCeiling() {
    String batch = "intent-it-ceiling-" + UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 7, 17);
    intents.recordScheduled(batch, session, true);

    assertThat(intents.claimableMissedSessionsBefore(batch, session.plusDays(1), 30, 2, 64))
        .as("armed, no run marker, no alert row yet")
        .containsExactly(session);

    alerts.claim(batch, session, 30, 2);
    expirePageLease(batch, session);
    assertThat(intents.claimableMissedSessionsBefore(batch, session.plusDays(1), 30, 2, 64))
        .as("one page taken, lease expired, still under the ceiling")
        .containsExactly(session);

    alerts.claim(batch, session, 30, 2);
    expirePageLease(batch, session);
    assertThat(intents.claimableMissedSessionsBefore(batch, session.plusDays(1), 30, 2, 64))
        .as("at the ceiling, so the scan stops offering it even with an expired lease")
        .isEmpty();
  }

  private void expirePageLease(String batch, LocalDate session) {
    jdbc.update(
        "UPDATE swing_missed_batch_alerts SET claimed_at = now() - interval '2 hours'"
            + " WHERE batch = ? AND session_date = ?",
        batch, java.sql.Date.valueOf(session));
  }

  @Test
  void missedSessionScanIsBoundedToTheMostRecentIntentRows() {
    String batch = "intent-it-bounded-" + UUID.randomUUID();
    LocalDate first = LocalDate.of(2026, 1, 1);
    for (int day = 0; day < 66; day++) {
      intents.recordScheduled(batch, first.plusDays(day), true);
    }

    assertThat(intents.claimableMissedSessionsBefore(batch, first.plusDays(66), 30, 5, 64))
        .hasSize(64)
        .startsWith(first.plusDays(2))
        .endsWith(first.plusDays(65));
  }
}
