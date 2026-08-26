package in.arthayantra.marketdata.kite.session.autologin;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The durable terminal marker against the REAL {@code marketdata.canary_runs} table.
 *
 * <p>⚠️ The unit tests around {@link KiteAutoLoginService} fake the JDBC boundary, so they prove the
 * ORCHESTRATION but say nothing about whether the SQL is valid, whether the {@code state} value
 * satisfies {@code canary_runs_state_ck}, or whether the {@code ON CONFLICT} target matches the real
 * primary key. A mocked {@code JdbcTemplate} accepts any string. This runs the actual statements.
 *
 * <p>⚠️ ITs share the singleton database with no per-method cleanup, so every method uses its own
 * {@code run_day} — the marker is keyed {@code (canary, run_day)} and a shared date would make one
 * method's row decide another's verdict.
 */
@SpringBootTest
class AutoLoginTerminalLedgerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private JdbcTemplate jdbc;

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-26T02:35:00Z"), ZoneOffset.UTC);

  private AutoLoginTerminalLedger ledger() {
    return new AutoLoginTerminalLedger(jdbc, CLOCK);
  }

  @Test
  @DisplayName("a day with no row reads PROCEED")
  void anUnmarkedDayProceeds() {
    assertThat(ledger().verdictFor(LocalDate.of(2031, 1, 6)))
        .isEqualTo(AutoLoginTerminalLedger.Verdict.PROCEED);
  }

  @Test
  @DisplayName("⚠️ the marker INSERTs against the real CHECK constraint and reads back TERMINAL")
  void markingADayPersistsAndBlocksIt() {
    // ⚠️ canary_runs_state_ck admits only CLAIMED or DONE. If this class ever invents a third value
    // the INSERT throws here — which is the whole reason this test exists rather than trusting the
    // mocked unit tests.
    LocalDate day = LocalDate.of(2031, 1, 7);
    AutoLoginTerminalLedger ledger = ledger();

    ledger.markTerminal(day);

    assertThat(ledger.verdictFor(day)).isEqualTo(AutoLoginTerminalLedger.Verdict.TERMINAL_RECORDED);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM canary_runs WHERE canary = ? AND run_day = ?",
                String.class,
                AutoLoginTerminalLedger.CANARY_KEY,
                day))
        .as("DONE, not CLAIMED: nothing is in flight and no later door may steal this day")
        .isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM canary_runs WHERE canary = ? AND run_day = ?",
                Boolean.class,
                AutoLoginTerminalLedger.CANARY_KEY,
                day))
        .as("a finished day carries a completion stamp")
        .isTrue();
  }

  @Test
  @DisplayName("marking twice is idempotent — the PRIMARY KEY absorbs the second write")
  void markingTwiceDoesNotThrowOrDuplicate() {
    // The transport chain's final failure can mark a day that an earlier refusal already marked.
    // ON CONFLICT DO NOTHING is what makes that a no-op rather than a DuplicateKeyException that
    // would propagate out of markTerminalForDay and suppress the owner's alert.
    LocalDate day = LocalDate.of(2031, 1, 8);
    AutoLoginTerminalLedger ledger = ledger();

    ledger.markTerminal(day);
    ledger.markTerminal(day);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM canary_runs WHERE canary = ? AND run_day = ?",
                Integer.class,
                AutoLoginTerminalLedger.CANARY_KEY,
                day))
        .isOne();
    assertThat(ledger.verdictFor(day)).isEqualTo(AutoLoginTerminalLedger.Verdict.TERMINAL_RECORDED);
  }

  @Test
  @DisplayName("the marker does not collide with the other canaries' rows for the same day")
  void theCanaryKeyIsItsOwnNamespace() {
    // canary_runs is shared with IngestCoverageCanary and EveningChainCanary. The PK is
    // (canary, run_day), so a same-day row under a different canary key must be invisible here —
    // otherwise an unrelated evening push would mute the next morning's login.
    LocalDate day = LocalDate.of(2031, 1, 9);
    jdbc.update(
        "INSERT INTO canary_runs (canary, run_day, state, source, claimed_at, completed_at)"
            + " VALUES (?, ?, 'DONE', 'SOME_OTHER_CANARY', now(), now())",
        "EVENING_CHAIN",
        day);

    assertThat(ledger().verdictFor(day))
        .as("another canary's row for the same day says nothing about the login")
        .isEqualTo(AutoLoginTerminalLedger.Verdict.PROCEED);
  }
}
