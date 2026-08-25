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
 * The {@code swing_catchup_runs} atomic claim (V047) against the REAL Timescale + flyway lineage — the
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

  /**
   * V060, and the defect a UNIT test could not see: {@code seedMissing} must refuse a session only
   * when its run marker says the ENTRIES ran, not merely that a row exists.
   *
   * <p>Since the 16:00/08:35 split the exits-only settle writes a marker for every session —
   * correctly, since it evaluated every held stop, which is what the 08:30 canary and 20:15
   * heartbeat ask about. A bare row-exists test here refuses to SEED, so the session never reaches
   * {@code retryableSessions}, the catch-up never sees it, and the 08:35 entry pass silently never
   * happens with every health signal green.
   *
   * <p>⚠️ This test exists because a unit test MASKED exactly that. {@code SwingBatchCatchUpTest}
   * fakes the retryable-state helper by filtering on {@code hasRunWithEntries}, which is not what
   * this SQL does — so the fix looked complete while the real statement still refused every
   * session. Cross-vendor review caught it one layer above where I had fixed it. The rule the
   * miss illustrates: when a guard lives in SQL, the test that proves it must run the SQL.
   */
  @Test
  void anExitsOnlyMarkerIsStillSeeded_butACompleteRunIsNot() {
    String exitsOnly = "cu-it-exits-" + System.nanoTime();
    String recoveryExits = "cu-it-recovery-" + System.nanoTime();
    String full = "cu-it-full-" + System.nanoTime();
    String legacy = "cu-it-legacy-" + System.nanoTime();

    // The 16:00 settle's marker: ran, but owes its entries.
    insertMarker(exitsOnly, SESSION, Boolean.FALSE);
    // ⚠️ The THIRD pass V063 added (cross-vendor review 2026-08-25): the catch-up's own exits-only
    // RECOVERY row. This gate still reads entries_enabled rather than `pass`, which is correct —
    // both exits passes write false, so the predicate is unchanged — but "correct by reasoning" is
    // what the class-level comment above says a SQL guard may never rely on, so it is measured.
    insertMarker(recoveryExits, SESSION, Boolean.FALSE, "RECOVERY_EXITS");
    // A complete run — nothing left to do for this session.
    insertMarker(full, SESSION, Boolean.TRUE);
    // A pre-V060 row: NULL reads as "entries ran", so it must NOT be re-seeded.
    insertMarker(legacy, SESSION, null);

    state.seedMissing(exitsOnly, java.util.List.of(SESSION));
    state.seedMissing(recoveryExits, java.util.List.of(SESSION));
    state.seedMissing(full, java.util.List.of(SESSION));
    state.seedMissing(legacy, java.util.List.of(SESSION));

    assertThat(seeded(exitsOnly))
        .as("an exits-only session still owes its entries and MUST be seeded")
        .isTrue();
    assertThat(seeded(recoveryExits))
        .as("a RECOVERY_EXITS row is exits-only too — that session still owes its entries")
        .isTrue();
    assertThat(seeded(full))
        .as("a complete run must not be re-seeded")
        .isFalse();
    assertThat(seeded(legacy))
        .as("a pre-V060 NULL row reads as entries-ran — re-seeding it would replay history")
        .isFalse();
  }

  /** A session with NO marker at all is seeded, exactly as before V060. */
  @Test
  void aSessionWithNoMarkerAtAllIsStillSeeded() {
    String batch = "cu-it-nomarker-" + System.nanoTime();

    state.seedMissing(batch, java.util.List.of(SESSION));

    assertThat(seeded(batch)).isTrue();
  }

  private void insertMarker(String batch, LocalDate session, Boolean entriesEnabled) {
    // V063 made `pass` NOT NULL and part of the key. Derived here with the SAME rule as the
    // migration's BACKFILL — which is a rule about EXISTING rows, not about new writes; production
    // declares the pass at the call site (SwingBatchRunRepository.Pass). `entries_enabled IS FALSE`
    // backfills to SETTLE and everything else INCLUDING NULL to ENTRIES, because a pre-V060 row was
    // written by a 20:00 batch that did both halves.
    insertMarker(batch, session, entriesEnabled, Boolean.FALSE.equals(entriesEnabled) ? "SETTLE" : "ENTRIES");
  }

  private void insertMarker(String batch, LocalDate session, Boolean entriesEnabled, String pass) {
    jdbc.update(
        """
        INSERT INTO swing_batch_runs
            (batch, run_date, pass, ran_at, strategies, candidates, entries, exits, exit_skipped,
             entries_enabled)
        VALUES (?, ?, ?, now(), 1, 0, 0, 0, 0, ?)
        """,
        batch,
        java.sql.Date.valueOf(session),
        pass,
        entriesEnabled);
  }

  private boolean seeded(String batch) {
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
            Integer.class,
            batch,
            java.sql.Date.valueOf(SESSION));
    return rows != null && rows > 0;
  }

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
  void aPendingRowIsReclaimableWithinTheSameDayWithoutSpendingTheBudget() {
    // ⚠️ This test used to assert attempts == 2 here, pinning "attempts counts CLAIMS". That was
    // only ever an accident of the schedule: with one catch-up run per morning, claims and IST days
    // were the same number. The budget's own exhaustion alert says "a held position's daily bar is
    // still missing" and the sweep window is expressed in max-attempts + 2 trading DAYS — it always
    // meant days. Counting claims means widening a cron silently shortens the recovery budget, and
    // at the default of 5 a six-pass morning marks the session ABANDONED ("UNRECOVERABLE") before
    // that morning is over.
    String batch = "cu-it-pending-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent(); // day 1, attempt 1
    state.markPending(batch, SESSION);

    Optional<SwingCatchUpStateRepository.Claim> retry = state.claim(batch, SESSION, 30);

    assertThat(retry).as("a PENDING partial is retryable").isPresent();
    assertThat(retry.get().attempts())
        .as("a second claim on the SAME IST day is a retry, not another day of trying")
        .isEqualTo(1);
  }

  /**
   * The budget counts IST days, and a once-a-day caller sees exactly what it always saw.
   *
   * <p>⚠️ Runs the real SQL, because the fix IS the SQL — a {@code CASE} comparing {@code
   * claimed_at}'s IST date to today's. The sibling test above records what happens when a guard that
   * lives in SQL is proved by a unit test instead.
   */
  /**
   * ⚠️ THE PRODUCTION PATH, and the one the first cut of this test missed. Every real session is
   * SEEDED before it is claimed, and a seeded row has {@code attempts = 0} with {@code claimed_at}
   * unset — so the day comparison sees NULL, which is neither less-than nor equal, and without an
   * explicit NULL arm the first genuine claim returns attempt ZERO. A five-day budget would then
   * run for six, silently.
   *
   * <p>The sibling test below claims a row that does not exist yet, which takes the INSERT arm of
   * the upsert. That is the EXCEPTIONAL path. Cross-vendor review caught the gap; the shape is the
   * catalogue's "a test that supplies its own input" — it built the one starting state the bug
   * could not occur in.
   */
  @Test
  void aSeededRowStartsAtAttemptOneNotZero() {
    String batch = "cu-it-seeded-" + System.nanoTime();
    state.seedMissing(batch, java.util.List.of(SESSION));

    assertThat(state.claim(batch, SESSION, 30).orElseThrow().attempts())
        .as(
            "a seeded row has claimed_at NULL, so the first real claim must still count as day ONE"
                + " — returning 0 here stretches every budget by a day")
        .isEqualTo(1);
  }

  @Test
  void attemptsCountIstDaysNotClaims() {
    String batch = "cu-it-budget-" + System.nanoTime();

    assertThat(state.claim(batch, SESSION, 30).orElseThrow().attempts()).isEqualTo(1);
    // Five more passes in the same morning — the shape a 10-minute poll produces.
    for (int pass = 0; pass < 5; pass++) {
      state.markPending(batch, SESSION);
      assertThat(state.claim(batch, SESSION, 30).orElseThrow().attempts())
          .as("pass %d of one morning must not spend a day of budget", pass + 2)
          .isEqualTo(1);
    }

    // Now genuinely cross days. Five more DAYS must reach 6 — i.e. one past the default budget of
    // 5, which is the point at which the caller abandons. Byte-identical to the old behaviour for a
    // once-a-day caller, which is the property that makes this change safe to deploy.
    for (int day = 2; day <= 6; day++) {
      state.markPending(batch, SESSION);
      backdateClaimByOneDay(batch);
      assertThat(state.claim(batch, SESSION, 30).orElseThrow().attempts())
          .as("day %d of trying", day)
          .isEqualTo(day);
    }
  }

  /** Moves the row's last claim back one full day so the next claim lands on a later IST date. */
  private void backdateClaimByOneDay(String batch) {
    jdbc.update(
        "UPDATE swing_catchup_runs SET claimed_at = claimed_at - interval '1 day'"
            + " WHERE batch = ? AND session_date = ?",
        batch, java.sql.Date.valueOf(SESSION));
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

  /**
   * The production ordering, and the reason DISARMED had never once been written. {@code
   * SwingBatchCatchUp.catchUp} seeds the window BEFORE it sweeps, and the sweep only iterates what
   * {@code retryableSessions} returns — so every session that reaches the disarm branch already has a
   * PENDING placeholder, and the old {@code ON CONFLICT DO NOTHING} was swallowed 100% of the time.
   * Live ledger, 2026-08-13: zero DISARMED rows, ever.
   *
   * <p>⚠️ Seeded with {@link SwingCatchUpStateRepository#seedMissing}, deliberately — NOT with {@code
   * claim} + {@code markPending}. Seeding it that second way is how the defect stayed invisible: it
   * builds a row with {@code attempts = 1} and {@code claimed_at} set, which is a genuinely-worked
   * row and the one starting state in which the bug cannot occur. The sibling test below is that
   * shape on purpose, and it is the guard, not the proof.
   */
  @Test
  void recordDisarmedTakesOverAnUntouchedSeedPlaceholder() {
    String batch = "cu-it-disarmed-seeded-" + System.nanoTime();
    state.seedMissing(batch, java.util.List.of(SESSION)); // the real placeholder path

    state.recordDisarmed(batch, SESSION);

    assertThat(statusOf(batch))
        .as("a seeded PENDING placeholder must be taken over, not silently left PENDING forever")
        .isEqualTo("DISARMED");
    assertThat(reasonOf(batch)).isEqualTo("DISARMED_AT_SCHEDULE_TIME");
    assertThat(state.claim(batch, SESSION, 30))
        .as("and the takeover is terminal — a disarmed session is never caught up")
        .isEmpty();
    assertThat(state.retryableSessions(batch, 30))
        .as("nor re-scanned by every later sweep")
        .isEmpty();
  }

  @Test
  void recordDisarmedNeverOverwritesAnExistingRow() {
    // A session already being worked (e.g. a PENDING partial after a real claim) is not clobbered to
    // DISARMED — claimed_at is set and attempts spent, so the takeover above refuses it.
    String batch = "cu-it-disarmed-noclobber-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markPending(batch, SESSION);

    state.recordDisarmed(batch, SESSION); // must be a no-op — a real, worked PENDING row exists

    assertThat(statusOf(batch)).as("the worked row keeps its own status").isEqualTo("PENDING");
    assertThat(state.claim(batch, SESSION, 30)).as("the PENDING survives, still retryable").isPresent();
  }

  private String statusOf(String batch) {
    return jdbc.queryForObject(
        "SELECT status FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
        String.class, batch, java.sql.Date.valueOf(SESSION));
  }

  private String reasonOf(String batch) {
    return jdbc.queryForObject(
        "SELECT reason FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
        String.class, batch, java.sql.Date.valueOf(SESSION));
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
    // ⚠️ Deliberately NO assertion on attempts here. The lease is measured in MINUTES and the budget
    // in IST DAYS, so a 2-hour back-date lands on the previous day whenever this runs between 00:00
    // and 02:00 IST and on the same day otherwise. Asserting either number would make this test
    // pass or fail by wall-clock time — a flake that only ever reproduces overnight. The counter has
    // its own test (attemptsCountIstDaysNotClaims) which controls both timestamps; this one is
    // about reclaimability.
    // A fresh RUNNING claim right after is again blocked (the reclaim reset claimed_at to now).
    assertThat(state.claim(batch, SESSION, 30)).isEmpty();
  }

  @Test
  void retryableSessionsIncludesPendingAndStaleRunningRows() {
    String batch = "cu-it-retryable-" + System.nanoTime();
    LocalDate pending = SESSION.minusDays(2);
    LocalDate stale = SESSION.minusDays(1);
    assertThat(state.claim(batch, pending, 30)).isPresent();
    state.markPending(batch, pending);
    assertThat(state.claim(batch, stale, 30)).isPresent();
    jdbc.update(
        "UPDATE swing_catchup_runs SET claimed_at = now() - interval '2 hours'"
            + " WHERE batch = ? AND session_date = ?",
        batch, java.sql.Date.valueOf(stale));

    assertThat(state.retryableSessions(batch, 30)).containsExactly(pending, stale);
  }

  @Test
  void abandonedStatePersistsTheReason() {
    String batch = "cu-it-abandon-reason-" + System.nanoTime();
    state.markAbandoned(batch, SESSION, "ATTEMPT_BUDGET_EXHAUSTED");

    String reason =
        jdbc.queryForObject(
            "SELECT reason FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
            String.class, batch, java.sql.Date.valueOf(SESSION));
    assertThat(reason).isEqualTo("ATTEMPT_BUDGET_EXHAUSTED");
  }

  @Test
  void structuredRefusalReasonSurvivesRetryAndAbandonment() {
    String batch = "cu-it-mixed-reason-" + System.nanoTime();
    String refusal = "MIXED_PRE_POST_LOTS:TESTCO";
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markPending(batch, SESSION, refusal);
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    state.markAbandoned(batch, SESSION, "ATTEMPT_BUDGET_EXHAUSTED");

    String reason =
        jdbc.queryForObject(
            "SELECT reason FROM swing_catchup_runs WHERE batch = ? AND session_date = ?",
            String.class, batch, java.sql.Date.valueOf(SESSION));
    assertThat(reason).isEqualTo(refusal);
  }

  /**
   * The orphan release (cross-vendor review, 2026-08-21) — against the REAL statement, because the
   * unit test cannot see it.
   *
   * <p>⚠️ <b>Why this exists, and it is the same miss as the one two tests above.</b> The boot
   * door's unit test fakes {@code releaseClaimsFrom} with a stub that moves its own fixture set back
   * to pending and <b>ignores the cutoff entirely</b>, and it verifies only {@code
   * releaseClaimsFrom(any())}. So it stays green if the real SQL binds the wrong timestamp, matches
   * the wrong statuses, or releases claims NEWER than process start — the last of which would hand a
   * live claim to a second caller and re-open the doubled-entry hole the whole table exists to
   * close. A mock that implements the desired result is not evidence the statement produces it.
   *
   * <p>The cutoff is read from the DATABASE clock rather than {@code Instant.now()}, so the test
   * asserts the predicate rather than the host-vs-container clock skew.
   */
  @Test
  void releaseClaimsFromFreesOnlyRunningClaimsOlderThanTheCutoff() {
    String old = "cu-it-orphan-old-" + System.nanoTime();
    String fresh = "cu-it-orphan-fresh-" + System.nanoTime();
    String done = "cu-it-orphan-done-" + System.nanoTime();
    String abandoned = "cu-it-orphan-abandoned-" + System.nanoTime();
    String pending = "cu-it-orphan-pending-" + System.nanoTime();

    assertThat(state.claim(old, SESSION, 30)).isPresent();
    assertThat(state.claim(fresh, SESSION, 30)).isPresent();
    assertThat(state.claim(done, SESSION, 30)).isPresent();
    state.markDone(done, SESSION);
    state.markAbandoned(abandoned, SESSION, "ATTEMPT_BUDGET_EXHAUSTED");
    assertThat(state.claim(pending, SESSION, 30)).isPresent();
    state.markPending(pending, SESSION, "EXECUTION_FAILED");

    // ⚠️ Backdate EVERY non-`fresh` fixture, not just `old`. First cut backdated only `old`, which
    // left the terminal and PENDING rows newer than the cutoff — so the TIMESTAMP predicate excluded
    // them and the STATUS predicate was never exercised. A red-proof that broadened the statuses to
    // ('RUNNING','DONE','ABANDONED') stayed GREEN, which is a finding about the test, not a pass.
    // With them backdated, status is the ONLY thing that can spare them.
    for (String batch : java.util.List.of(old, done, abandoned, pending)) {
      jdbc.update(
          "UPDATE swing_catchup_runs SET claimed_at = now() - interval '2 hours'"
              + " WHERE batch = ? AND session_date = ?",
          batch, java.sql.Date.valueOf(SESSION));
    }
    java.time.Instant cutoff =
        jdbc.queryForObject("SELECT now() - interval '1 hour'", java.sql.Timestamp.class).toInstant();

    int released = state.releaseClaimsFrom(cutoff);

    assertThat(released)
        .as("exactly the one RUNNING claim older than the cutoff — a count that grows with the"
            + " fixture is the tell that the predicate is not doing the filtering")
        .isEqualTo(1);
    assertThat(statusOf(old)).isEqualTo("PENDING");
    assertThat(reasonOf(old)).isEqualTo("ORPHANED_BY_RESTART");
    assertThat(state.claim(old, SESSION, 30))
        .as("and it is genuinely reclaimable again — PENDING that cannot be claimed is not a fix")
        .isPresent();

    assertThat(statusOf(fresh))
        .as("a claim taken AFTER the cutoff is live; releasing it would hand a running session to a"
            + " second caller, which is the doubled entry this table exists to prevent")
        .isEqualTo("RUNNING");
    assertThat(statusOf(done)).as("terminal DONE must not be resurrected").isEqualTo("DONE");
    assertThat(statusOf(abandoned)).isEqualTo("ABANDONED");
    assertThat(statusOf(pending)).isEqualTo("PENDING");
    assertThat(reasonOf(pending))
        .as("an already-PENDING row keeps its own refusal reason — the release must not relabel"
            + " rows it did not free")
        .isEqualTo("EXECUTION_FAILED");
  }

  @Test
  void releaseClaimsFromIsANoOpWhenNothingIsOrphaned() {
    String batch = "cu-it-orphan-noop-" + System.nanoTime();
    assertThat(state.claim(batch, SESSION, 30)).isPresent();
    java.time.Instant cutoff =
        jdbc.queryForObject("SELECT now() - interval '1 hour'", java.sql.Timestamp.class).toInstant();

    assertThat(state.releaseClaimsFrom(cutoff))
        .as("the ordinary boot: nothing died holding a claim, so nothing is touched")
        .isZero();
    assertThat(statusOf(batch)).isEqualTo("RUNNING");
  }

  @Test
  void latestReturnsTheMostRecentlyUpdatedRowAndEmptyForAnUnknownBatch() {
    String batch = "cu-it-latest-" + System.nanoTime();
    state.markAbandoned(batch, SESSION, "NO_SCHEDULE_INTENT");

    Optional<SwingCatchUpStateRepository.LatestCatchUpRow> latest = state.latest(batch);

    assertThat(latest).isPresent();
    assertThat(latest.get().sessionDate()).isEqualTo(SESSION);
    assertThat(latest.get().status()).isEqualTo("ABANDONED");
    assertThat(latest.get().attempts()).isZero();
    assertThat(latest.get().reason()).isEqualTo("NO_SCHEDULE_INTENT");
    assertThat(latest.get().updatedAt()).isNotNull();
    assertThat(state.latest("cu-it-no-such-batch-" + System.nanoTime())).isEmpty();
  }
}
