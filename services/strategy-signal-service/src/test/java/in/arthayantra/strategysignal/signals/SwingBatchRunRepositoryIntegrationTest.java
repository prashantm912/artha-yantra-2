package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.signals.SwingBatchRunRepository.Pass;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the V025 {@code swing_batch_runs} marker upsert against the real schema (audit P0-4 review):
 * the marker table's only consumer is a SAFETY mechanism — a silent SQL/schema defect here disarms
 * the missed-batch detector, and it fails in the quiet direction (a marker that never lands reads as
 * "the batch did not run", so the detector pages for sessions that were fine, or — if {@code hasRun}
 * wrongly answered true — swallows a real miss). So the round-trip + re-stamp are worth pinning.
 * Since V034 (ledger F3) the same row carries the admission probe — {@link #recentProbesRoundTrips}
 * pins the JSONB dropped-by-cap column + the newest-first read.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingBatchRunRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingBatchRunRepository repo;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void recordThenReadBackTheMarkerAndReStampSameDate() {
    // Unique batch name so the singleton-DB, no-cleanup IT convention holds.
    String batch = "it-mv-" + java.util.UUID.randomUUID();
    LocalDate d1 = LocalDate.of(2026, 7, 3);
    LocalDate d2 = LocalDate.of(2026, 7, 6);

    repo.record(batch, d1, 4, 20, 3, 1, 0, 2, 5, 3, 2, true, List.of(), Pass.ENTRIES);
    assertThat(repo.hasRun(batch, d1)).isTrue();
    assertThat(repo.hasRun(batch, d2)).isFalse();

    // A later date adds its own marker without disturbing the earlier one.
    repo.record(batch, d2, 4, 25, 5, 2, 1, 3, 7, 5, 2, true, List.of(), Pass.ENTRIES);
    assertThat(repo.hasRun(batch, d2)).isTrue();
    assertThat(repo.hasRun(batch, d1)).isTrue();

    // Re-stamping the SAME date is an upsert (no duplicate-key blow-up) that overwrites the counters.
    repo.record(batch, d2, 4, 30, 6, 4, 0, 0, 6, 6, 0, false, List.of(), Pass.ENTRIES);
    assertThat(repo.hasRun(batch, d2)).isTrue();
    Integer entries =
        jdbc.queryForObject(
            "SELECT entries FROM swing_batch_runs WHERE batch = ? AND run_date = ?",
            Integer.class, batch, java.sql.Date.valueOf(d2));
    assertThat(entries).isEqualTo(6);
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM swing_batch_runs WHERE batch = ?", Integer.class, batch);
    assertThat(rows).isEqualTo(2); // two distinct dates, the re-stamp did NOT add a third row
  }

  /**
   * V060, and it is the join that makes the 16:00/08:35 split safe.
   *
   * <p>The 16:00 exits-only settle writes a marker row because it genuinely evaluated every held
   * stop — that is what the 08:30 canary and the 20:15 heartbeat ask about, so {@code hasRun} must
   * still say true for it. {@link SwingBatchRunRepository#hasRunWithEntries} answers the DIFFERENT
   * question the catch-up asks: does this session still owe its entries. Conflating the two would
   * make the catch-up skip every session and the book would never take another entry.
   *
   * <p>⚠️ The last assertion used to pin a MONOTONE UPSERT and now pins something stronger. Before
   * V063 both passes shared one row, so a later exits-only re-run could take {@code entries_enabled}
   * back to false and re-open a session the catch-up had already entered — the OR in the upsert was
   * what stopped it. Since V063 the passes hold SEPARATE rows, so the ENTRIES row cannot be written
   * by an exits-only run at all and there is nothing to downgrade. Same assertion, and it must keep
   * passing; the mechanism underneath it changed and the OR is deleted.
   */
  @Test
  void entriesEnabledSeparatesAnExitsOnlySettleFromASessionThatOwesNoEntries() {
    String batch = "it-split-" + java.util.UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 7, 7);

    // 16:00 settle: exits ran, entries did not.
    repo.record(batch, session, 4, 0, 0, 2, 0, 6, 0, 0, 0, false, List.of(), Pass.SETTLE);
    assertThat(repo.hasRun(batch, session)).isTrue(); // the canary and heartbeat stay quiet…
    assertThat(repo.hasRunWithEntries(batch, session)).isFalse(); // …and the catch-up still owes it

    // 08:35 catch-up: entries taken for the same session.
    repo.record(batch, session, 4, 118, 2, 0, 0, 6, 5, 2, 0, false, List.of(), Pass.ENTRIES);
    assertThat(repo.hasRunWithEntries(batch, session)).isTrue();

    // A LATER exits-only re-run must not downgrade it back to owing entries.
    repo.record(batch, session, 4, 0, 0, 1, 0, 8, 0, 0, 0, false, List.of(), Pass.SETTLE);
    assertThat(repo.hasRunWithEntries(batch, session)).isTrue();
  }

  /**
   * A pre-V060 row carries {@code entries_enabled} NULL and must read as entries-ran. Every
   * historical row was written by a full batch, so resolving NULL the other way would hand the
   * catch-up every past session at once on the deploy that adds the column.
   *
   * <p>⚠️ <b>Where that rule now lives changed, and this test would otherwise pass without
   * checking it.</b> Since V063 {@code hasRunWithEntries} reads {@code pass}, not
   * {@code entries_enabled}, so nulling the flag proves nothing on its own — the rule moved into
   * V063's backfill ({@code entries_enabled IS FALSE -> 'SETTLE'}, everything else including NULL
   * {@code -> 'ENTRIES'}). The second half below is what makes this test still mean something: it
   * pins that the query IGNORES the flag in BOTH directions, so a SETTLE row reads false even with
   * the flag nulled to the legacy value that used to force true.
   */
  @Test
  void aLegacyRowWithNoEntriesEnabledFlagIsReadAsHavingRunItsEntries() {
    String batch = "it-legacy-" + java.util.UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 7, 8);

    repo.record(batch, session, 4, 20, 3, 1, 0, 2, 5, 3, 0, false, List.of(), Pass.ENTRIES);
    jdbc.update(
        "UPDATE swing_batch_runs SET entries_enabled = NULL WHERE batch = ? AND run_date = ?",
        batch, java.sql.Date.valueOf(session));

    assertThat(repo.hasRunWithEntries(batch, session)).isTrue();

    // …and an exits-only re-stamp still must not drop it to false (now a separate SETTLE row).
    repo.record(batch, session, 4, 0, 0, 1, 0, 5, 0, 0, 0, false, List.of(), Pass.SETTLE);
    assertThat(repo.hasRunWithEntries(batch, session)).isTrue();

    // The other direction: a SETTLE row whose flag is nulled to the legacy value must still read
    // false. If hasRunWithEntries still consulted entries_enabled, COALESCE(NULL, true) would make
    // this TRUE and the catch-up would skip a session that never took its entries.
    String settleOnly = "it-legacy-settle-" + java.util.UUID.randomUUID();
    repo.record(settleOnly, session, 4, 0, 0, 2, 0, 6, 0, 0, 0, false, List.of(), Pass.SETTLE);
    jdbc.update(
        "UPDATE swing_batch_runs SET entries_enabled = NULL WHERE batch = ? AND run_date = ?",
        settleOnly, java.sql.Date.valueOf(session));
    assertThat(repo.hasRunWithEntries(settleOnly, session)).isFalse();
  }

  /**
   * ⚠️ LEDGER H23, and this is the regression test for a defect that had already destroyed real
   * data by the time it was found.
   *
   * <p>The evening exits-only SETTLE and the next morning's ENTRIES catch-up write the same
   * {@code (batch, run_date)}. Under V025's key that was ONE row and the second write won, so the
   * settle's exit count was replaced by the catch-up's — measured 2026-08-25, every row for
   * {@code run_date} 2026-08-17..2026-08-21 carried a {@code ran_at} from the next morning.
   *
   * <p>What must hold: both rows survive, each keeps its OWN counters, and neither can be reached
   * by the other's upsert. The exits assertion is the one that fails against the old key.
   */
  @Test
  void theSettleAndTheCatchUpKeepSeparateRowsForTheSameSession() {
    String batch = "it-h23-" + java.util.UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 8, 24);

    // 18:52 settle: two stops fired, no entries.
    repo.record(batch, session, 2, 0, 0, 2, 0, 8, 0, 0, 0, false, List.of(), Pass.SETTLE);
    // 08:35 next morning: the catch-up takes entries and sees no exit of its own.
    repo.record(batch, session, 2, 118, 3, 0, 0, 8, 5, 3, 0, false, List.of(), Pass.ENTRIES);

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM swing_batch_runs WHERE batch = ? AND run_date = ?",
            Integer.class, batch, java.sql.Date.valueOf(session));
    assertThat(rows).as("one row per PASS, not one per session").isEqualTo(2);

    Integer settleExits =
        jdbc.queryForObject(
            "SELECT exits FROM swing_batch_runs WHERE batch = ? AND run_date = ? AND pass = 'SETTLE'",
            Integer.class, batch, java.sql.Date.valueOf(session));
    assertThat(settleExits)
        .as("the settle's exit count must survive the catch-up — this is the H23 defect")
        .isEqualTo(2);

    Integer entriesTaken =
        jdbc.queryForObject(
            "SELECT entries FROM swing_batch_runs WHERE batch = ? AND run_date = ? AND pass = 'ENTRIES'",
            Integer.class, batch, java.sql.Date.valueOf(session));
    assertThat(entriesTaken).isEqualTo(3);

    // Both questions the two consumers ask are still answered correctly.
    assertThat(repo.hasRun(batch, session)).isTrue();
    assertThat(repo.hasRunWithEntries(batch, session)).isTrue();

    // …and the probe endpoint sees the ENTRIES row only, never a settle row padded with zeros.
    assertThat(repo.recentProbes(batch, 10)).hasSize(1);
    assertThat(repo.recentProbes(batch, 10).get(0).entries()).isEqualTo(3);
  }

  /**
   * ⚠️ THE CRITICAL the first cut of V063 did not close, raised in cross-vendor review 2026-08-25.
   *
   * <p>{@link #theSettleAndTheCatchUpKeepSeparateRowsForTheSameSession} above covers settle → a
   * SUCCESSFUL entries catch-up, and that pair is separable by {@code entries_enabled} alone
   * (false, then true). It is the pair that is NOT separable that had to be tested: there are THREE
   * operational origins and TWO of them run with entries disabled. Between the evening settle and
   * the morning entry pass sits the catch-up's exits-only RECOVERY — arming authoritative, funnel
   * not as-of the session ({@code SwingBatchCatchUp:643-645}) — which writes a marker under {@code
   * MarkerPolicy.ON_COMPLETE} with entries disabled, exactly like the settle.
   *
   * <p>Derive the pass from that flag and the recovery row lands on the settle's key and destroys
   * it, which is the defect V063 exists to fix. And not once: that branch marks the session {@code
   * SCREEN_NOT_AS_OF_SESSION} → PENDING, so it stays retryable and overwrites the settle again on
   * every later sweep. Hence the third step below — a SECOND recovery, with different counters —
   * which is the assertion the derived form cannot survive.
   *
   * <p>Ordered as it happens on the clock: 18:52 settle, then the next morning's sweeps.
   */
  @Test
  void theSettleTheRecoveryAndTheEntryPassEachKeepTheirOwnRow() {
    String batch = "it-h23-3pass-" + java.util.UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 8, 21);

    // 18:52 — the scheduled settle. Two stops fired off this session's own bar.
    repo.record(batch, session, 2, 0, 0, 2, 0, 8, 0, 0, 0, false, List.of(), Pass.SETTLE);
    // 08:35 — the catch-up finds the screen is not as-of this session. Exits only, entries withheld,
    // and it saw one further stop the settle had not. The session stays retryable.
    repo.record(batch, session, 2, 0, 0, 1, 0, 7, 0, 0, 0, false, List.of(), Pass.RECOVERY_EXITS);
    // A LATER sweep, same shape, different counters — the retry the PENDING state guarantees.
    repo.record(batch, session, 2, 0, 0, 3, 0, 7, 0, 0, 0, false, List.of(), Pass.RECOVERY_EXITS);
    // …and finally the screen lands, so the entry pass runs for the same session.
    repo.record(batch, session, 2, 118, 4, 0, 0, 7, 6, 4, 0, false, List.of(), Pass.ENTRIES);

    assertThat(passRows(batch, session))
        .as("three ORIGINS, three rows — the recovery must not share the settle's key")
        .containsExactlyInAnyOrder("ENTRIES", "RECOVERY_EXITS", "SETTLE");

    assertThat(intFor(batch, session, "SETTLE", "exits"))
        .as("the settle's own exit count, untouched by either recovery sweep")
        .isEqualTo(2);
    assertThat(intFor(batch, session, "RECOVERY_EXITS", "exits"))
        .as("the recovery re-stamps its OWN row — 3 from the second sweep, not the settle's 2")
        .isEqualTo(3);
    assertThat(intFor(batch, session, "ENTRIES", "entries")).isEqualTo(4);

    // entries_enabled is written FROM the declared pass, so it agrees with it for every row.
    assertThat(flagFor(batch, session, "SETTLE")).isFalse();
    assertThat(flagFor(batch, session, "RECOVERY_EXITS")).isFalse();
    assertThat(flagFor(batch, session, "ENTRIES")).isTrue();

    // The three consumers still answer correctly with a third row in play.
    assertThat(repo.hasRun(batch, session)).as("the canary + heartbeat dead-man").isTrue();
    assertThat(repo.hasRunWithEntries(batch, session))
        .as("excludes BOTH exits-flavoured passes, not just SETTLE")
        .isTrue();
    assertThat(repo.recentProbes(batch, 10))
        .as("the probe read must not be padded with two exits rows carrying zeros")
        .hasSize(1);
    assertThat(repo.recentProbes(batch, 10).get(0).entries()).isEqualTo(4);
  }

  /**
   * A RECOVERY_EXITS row alone must read as still owing its entries — the same answer a bare SETTLE
   * gives. If it did not, the catch-up would skip a session whose entries never ran.
   */
  @Test
  void aRecoveryExitsRowAloneStillOwesItsEntries() {
    String batch = "it-h23-recov-" + java.util.UUID.randomUUID();
    LocalDate session = LocalDate.of(2026, 8, 20);

    repo.record(batch, session, 2, 0, 0, 1, 0, 4, 0, 0, 0, false, List.of(), Pass.RECOVERY_EXITS);

    assertThat(repo.hasRun(batch, session)).isTrue();
    assertThat(repo.hasRunWithEntries(batch, session)).isFalse();
    assertThat(repo.recentProbes(batch, 10)).isEmpty();
  }

  private List<String> passRows(String batch, LocalDate session) {
    return jdbc.queryForList(
        "SELECT pass FROM swing_batch_runs WHERE batch = ? AND run_date = ?",
        String.class, batch, java.sql.Date.valueOf(session));
  }

  private Integer intFor(String batch, LocalDate session, String pass, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM swing_batch_runs WHERE batch = ? AND run_date = ? AND pass = ?",
        Integer.class, batch, java.sql.Date.valueOf(session), pass);
  }

  private Boolean flagFor(String batch, LocalDate session, String pass) {
    return jdbc.queryForObject(
        "SELECT entries_enabled FROM swing_batch_runs WHERE batch = ? AND run_date = ? AND pass = ?",
        Boolean.class, batch, java.sql.Date.valueOf(session), pass);
  }

  @Test
  void recentProbesRoundTrips() {
    String batch = "it-probe-" + java.util.UUID.randomUUID();
    LocalDate d1 = LocalDate.of(2026, 7, 3);
    LocalDate d2 = LocalDate.of(2026, 7, 6);

    // d1: the cap did NOT bind (nobody dropped). d2: it bound, dropping two RS-ordered names.
    repo.record(batch, d1, 4, 20, 3, 1, 0, 2, 3, 3, 0, false, List.of(), Pass.ENTRIES);
    repo.record(
        batch, d2, 4, 25, 12, 2, 0, 4, 15, 12, 3, true,
        List.of(new DroppedCandidate("ZEEL", 13), new DroppedCandidate("IDEA", 14),
            new DroppedCandidate("PNB", 15)), Pass.ENTRIES);

    List<SwingBatchRunRepository.ProbeRow> probes = repo.recentProbes(batch, 10);
    assertThat(probes).hasSize(2);
    // Newest first.
    SwingBatchRunRepository.ProbeRow latest = probes.get(0);
    assertThat(latest.runDate()).isEqualTo(d2);
    assertThat(latest.wouldEnter()).isEqualTo(15);
    assertThat(latest.admitted()).isEqualTo(12);
    assertThat(latest.capExceedance()).isEqualTo(3);
    assertThat(latest.capBound()).isTrue();
    assertThat(latest.droppedByCap())
        .extracting(DroppedCandidate::symbol)
        .containsExactly("ZEEL", "IDEA", "PNB");
    assertThat(latest.droppedByCap().get(2).admissionRank()).isEqualTo(15);
    // The non-binding day round-trips an empty JSONB array (not null).
    assertThat(probes.get(1).capBound()).isFalse();
    assertThat(probes.get(1).droppedByCap()).isEmpty();
  }

  /**
   * A batch that has never recorded must read as NOT run rather than throwing or defaulting true —
   * the detector calls this for every candidate session, so a wrong answer here either buries a real
   * miss or invents one.
   */
  @Test
  void hasRunIsFalseForANeverRecordedBatch() {
    assertThat(repo.hasRun("it-never-" + java.util.UUID.randomUUID(), LocalDate.of(2026, 7, 3)))
        .isFalse();
  }
}
