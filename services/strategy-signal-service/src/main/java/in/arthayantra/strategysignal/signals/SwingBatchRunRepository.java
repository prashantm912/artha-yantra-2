package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code swing_batch_runs} dead-man marker (V025, audit P0-4/H10): each swing batch records
 * one row per IST run date PER PASS; {@code SwingBatchCanary} checks the exact historical session
 * next morning and alerts when a schedule-time armed batch has no matching row.
 *
 * <p>⚠️ <b>"Per pass" is V063 and it fixed a real loss of data (ledger H23).</b> V025 keyed this
 * table {@code (batch, run_date)} on the assumption that a session has one batch run. The
 * 16:00/08:35 split (#1333) broke that without changing the key: the evening exits-only SETTLE
 * writes the row, and the next morning's ENTRIES catch-up upserts the SAME key for the SAME
 * {@code run_date}, so the second write wins and the first is gone. Measured 2026-08-25 — every row
 * for {@code run_date} 2026-08-17..2026-08-21 carries a {@code ran_at} from the NEXT MORNING, so
 * five sessions' settle records no longer exist.
 *
 * <p>No merge rule could have fixed it, which is why the key changed instead: BOTH passes
 * legitimately compute every column. The settle counts the exits it fired at 18:52; the catch-up
 * counts the exits IT saw at 08:36 the next morning against different prices. Neither number is
 * wrong and neither is the other's, so a GREATEST or a COALESCE would just pick one and destroy the
 * attribution. No money was ever at risk — {@code paper_positions}, {@code swing_paper_effects} and
 * the P&L are correct and complete; what was destroyed is the batch-run AUDIT TRAIL, which is the
 * surface a forward-paper reliability verdict reads.
 *
 * <p>Since V034 (ledger F3) the same row also carries the batch's admission PROBE — the slot-cap
 * exceedance + the RS-ordered names the cap dropped ({@link #recentProbes}); the probe columns are
 * NULLABLE, so a pre-V034 row (or a flag-off no-op run) reads as "not probed".
 */
@Repository
public class SwingBatchRunRepository {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchRunRepository.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires the JDBC template + the mapper used to (de)serialize the dropped-by-cap JSONB. */
  public SwingBatchRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Upserts the marker + admission probe for one batch run (re-runs on the same date re-stamp the
   * counters). {@code droppedByCap} is serialized to the {@code dropped_by_cap} JSONB. Returns {@code
   * true} iff the canonical row was written — the catch-up marks a session terminally DONE only when
   * this is true (a fail-soft-swallowed write must leave the session repairable, not stuck DONE with
   * no marker; 2026-07-17 review Major).
   */
  public boolean record(
      String batch, LocalDate runDate, int strategies, int candidates, int entries, int exits,
      int exitSkipped, int openAtStart, int wouldEnter, int admitted, int capExceedance,
      boolean capBound, List<DroppedCandidate> droppedByCap, boolean entriesEnabled) {
    int rows =
        jdbc.update(
            """
            INSERT INTO swing_batch_runs
                (batch, run_date, pass, ran_at, strategies, candidates, entries, exits, exit_skipped,
                 open_at_start, would_enter, admitted, cap_exceedance, cap_bound, dropped_by_cap,
                 entries_enabled)
            VALUES (?, ?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (batch, run_date, pass) DO UPDATE SET
                ran_at = now(), strategies = EXCLUDED.strategies, candidates = EXCLUDED.candidates,
                entries = EXCLUDED.entries, exits = EXCLUDED.exits,
                exit_skipped = EXCLUDED.exit_skipped, open_at_start = EXCLUDED.open_at_start,
                would_enter = EXCLUDED.would_enter, admitted = EXCLUDED.admitted,
                cap_exceedance = EXCLUDED.cap_exceedance, cap_bound = EXCLUDED.cap_bound,
                dropped_by_cap = EXCLUDED.dropped_by_cap,
                -- ⚠️ A PLAIN assignment, and the monotone OR that used to be here is DELETED on
                -- purpose (V063 / ledger H23). That OR existed because the two passes shared one row:
                -- the exits pass wrote entries_enabled=false, the catch-up wrote true for the SAME
                -- key, and a later exits-only re-run could take the row back to false and re-open a
                -- session whose entries had already run. With `pass` in the primary key the two
                -- passes no longer share a row at all, so an ENTRIES row's flag is always true and a
                -- SETTLE row's always false. Keeping the OR here would be dead logic that reads as
                -- if the collision were still possible.
                entries_enabled = EXCLUDED.entries_enabled
            """,
            batch, java.sql.Date.valueOf(runDate), pass(entriesEnabled), strategies, candidates,
            entries, exits, exitSkipped, openAtStart, wouldEnter, admitted, capExceedance, capBound,
            writeDropped(droppedByCap), entriesEnabled);
    return rows > 0;
  }

  /**
   * Which pass a run belongs to — the discriminator V063 added to the primary key (ledger H23).
   *
   * <p>Derived from {@code entriesEnabled} rather than passed in, so there is exactly ONE place that
   * decides it and a caller cannot label a run's pass inconsistently with what it actually did.
   */
  private static String pass(boolean entriesEnabled) {
    return entriesEnabled ? "ENTRIES" : "SETTLE";
  }

  /** The latest recorded run date for a batch — empty when the batch has never recorded. */
  public Optional<LocalDate> lastRunDate(String batch) {
    return Optional.ofNullable(
        jdbc.queryForObject(
            "SELECT max(run_date) FROM swing_batch_runs WHERE batch = ?", LocalDate.class, batch));
  }

  /**
   * Whether a batch recorded a run for EXACTLY this IST session — the catch-up's completeness signal
   * and the detector's run-marker probe (the on-time run, or a COMPLETE catch-up, stamps one row per
   * session). {@code lastRunDate} tracks only the max, so it cannot answer "did session X run" when a
   * later session ran but X was skipped.
   */
  public boolean hasRun(String batch, LocalDate sessionDate) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM swing_batch_runs WHERE batch = ? AND run_date = ?)",
            Boolean.class, batch, java.sql.Date.valueOf(sessionDate)));
  }

  /**
   * Whether this session's ENTRY pass has run — {@link SwingBatchCatchUp}'s skip signal since the
   * 16:00/08:35 split (V060).
   *
   * <p>Distinct from {@link #hasRun} on purpose, and the distinction is the whole point of the
   * split. The 16:00 exits pass writes a marker row because it genuinely did evaluate every held
   * stop, which is what the 08:30 canary and the heartbeat ask about — so they keep using {@code
   * hasRun}. The catch-up asks a different question, "does this session still owe its entries", and
   * a bare row-exists answered it wrongly the moment an exits-only run could write one.
   *
   * <p>A pre-V060 row has {@code entries_enabled} NULL and is read as TRUE: every historical row was
   * written by a full batch, and treating those as owing entries would hand the catch-up every past
   * session at once.
   */
  public boolean hasRunWithEntries(String batch, LocalDate sessionDate) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM swing_batch_runs
                WHERE batch = ? AND run_date = ? AND pass = 'ENTRIES')
            """,
            Boolean.class, batch, java.sql.Date.valueOf(sessionDate)));
  }

  /**
   * The most recent probed runs for a batch, newest first (ledger F3). Only rows carrying a probe
   * (post-V034 armed runs) are returned — a pre-migration / no-op row reads as "not probed".
   */
  public List<ProbeRow> recentProbes(String batch, int limit) {
    return jdbc.query(
        """
        SELECT batch, run_date, ran_at, candidates, entries, open_at_start, would_enter, admitted,
               cap_exceedance, cap_bound, dropped_by_cap
        FROM swing_batch_runs
        -- ⚠️ pass = 'ENTRIES' is REQUIRED since V063, not a refinement. The admission probe is a
        -- property of the entry pass; the exits-only settle writes would_enter = 0 (not null), so
        -- before the key split it was indistinguishable here, and after the split BOTH rows exist
        -- for the same date — without this predicate a LIMIT n would silently return roughly half
        -- as many probed sessions as it used to, padded with settle rows carrying zeros.
        WHERE batch = ? AND pass = 'ENTRIES' AND would_enter IS NOT NULL
        ORDER BY run_date DESC
        LIMIT ?
        """,
        (rs, n) ->
            new ProbeRow(
                rs.getString("batch"),
                rs.getObject("run_date", LocalDate.class),
                rs.getObject("ran_at", OffsetDateTime.class),
                rs.getInt("candidates"),
                rs.getInt("entries"),
                rs.getInt("open_at_start"),
                rs.getInt("would_enter"),
                rs.getInt("admitted"),
                rs.getInt("cap_exceedance"),
                rs.getBoolean("cap_bound"),
                readDropped(rs.getString("dropped_by_cap"))),
        batch, limit);
  }

  private String writeDropped(List<DroppedCandidate> dropped) {
    try {
      return objectMapper.writeValueAsString(dropped == null ? List.of() : dropped);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn("dropped-by-cap serialization failed — persisting []: {}", e.getMessage());
      return "[]";
    }
  }

  private List<DroppedCandidate> readDropped(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<List<DroppedCandidate>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn("dropped-by-cap deserialization failed — returning []: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * One probed batch run for the read endpoint (ledger F3). {@code candidates}/{@code entries} carry the
   * existing marker counts for context; the probe fields answer "did the slot cap bind, by how much, and
   * which RS-ordered names did it drop".
   */
  public record ProbeRow(
      String batch,
      LocalDate runDate,
      OffsetDateTime ranAt,
      int candidates,
      int entries,
      int openAtStart,
      int wouldEnter,
      int admitted,
      int capExceedance,
      boolean capBound,
      List<DroppedCandidate> droppedByCap) {}

  /** The {items} envelope for the admission-probe read endpoint. */
  public record AdmissionProbes(List<ProbeRow> items) {}
}
