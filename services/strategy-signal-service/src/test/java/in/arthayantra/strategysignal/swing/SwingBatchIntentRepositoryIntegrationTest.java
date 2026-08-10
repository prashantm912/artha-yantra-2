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

  /**
   * ⚠️ BOTH transition directions, and one of them is a money path.
   *
   * <p>The intraday ticks write PROVISIONALLY ({@code ON CONFLICT DO NOTHING}), so without an
   * authoritative writer the first 09:05 observation would win permanently and the 16:00 settle
   * could never correct it. Cross-vendor review found both halves broken (2026-08-10):
   *
   * <ul>
   *   <li>armed at 09:05, DISARMED before the settle → intent stuck {@code true}, the settle writes
   *       no marker, and the catch-up replays the session through the explicitly-armed historical
   *       overload. A deliberately disabled family takes entries.
   *   <li>disarmed at 09:05, ARMED before the settle → intent stuck {@code false} and every entry
   *       for that session is forfeited while the family is live.
   * </ul>
   *
   * <p>Widening the tick window would not have helped: once the row exists every later tick is a
   * no-op. One writer has to be allowed to correct the others.
   */
  @Test
  void theSettleOverwritesAProvisionalTickInBothDirections() {
    LocalDate session = LocalDate.of(2026, 7, 21);

    String armedThenDisarmed = "it-intent-off-" + java.util.UUID.randomUUID();
    intents.recordScheduled(armedThenDisarmed, session, true); // 09:05 observation
    intents.recordScheduled(armedThenDisarmed, session, true); // a later tick: no-op
    intents.recordSettled(armedThenDisarmed, session, false); // 16:00, the authority
    assertThat(intents.find(armedThenDisarmed, session))
        .as("a family disarmed after the morning tick must NOT read as armed")
        .contains(false);

    String disarmedThenArmed = "it-intent-on-" + java.util.UUID.randomUUID();
    intents.recordScheduled(disarmedThenArmed, session, false);
    intents.recordSettled(disarmedThenArmed, session, true);
    assertThat(intents.find(disarmedThenArmed, session))
        .as("a family armed after the morning tick must not forfeit its entries")
        .contains(true);
  }

  /** With no settle at all, the provisional tick still stands — that is why the ticks exist. */
  @Test
  void aProvisionalTickSurvivesWhenTheSettleNeverRuns() {
    LocalDate session = LocalDate.of(2026, 7, 22);
    String batch = "it-intent-nosettle-" + java.util.UUID.randomUUID();

    intents.recordScheduled(batch, session, true);

    assertThat(intents.find(batch, session))
        .as("a container down at 16:00 must still leave the catch-up an intent row")
        .contains(true);
  }

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
