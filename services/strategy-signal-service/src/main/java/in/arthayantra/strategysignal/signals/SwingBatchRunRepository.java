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
 * <p>⚠️ <b>There are THREE origins, not two, and the first cut of V063 DERIVED {@link Pass} from
 * {@code entriesEnabled} — which cannot tell two of them apart</b> (cross-vendor review of this PR).
 * The evening settle and the catch-up's exits-only RECOVERY (arming authoritative, funnel not as-of
 * the session — {@code SwingBatchCatchUp:643-645}) BOTH run with entries disabled, so both derived
 * to {@code SETTLE} and the recovery went on destroying the settle's row exactly as before. Worse,
 * that branch leaves the session PENDING ({@code SCREEN_NOT_AS_OF_SESSION}), so it could overwrite
 * the settle again on every later sweep. The pass is therefore DECLARED by the call site and never
 * inferred from a flag that means something else, and {@code entries_enabled} is written FROM the
 * declared pass so the two can no longer disagree.
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
      boolean capBound, List<DroppedCandidate> droppedByCap, Pass pass) {
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
                -- session whose entries had already run. With `pass` in the primary key they no
                -- longer share a row, and entries_enabled is now written FROM the declared pass
                -- (Pass#takesEntries), so within one key it is a CONSTANT: an ENTRIES row's flag is
                -- always true, a SETTLE or RECOVERY_EXITS row's always false. There is nothing left
                -- for an OR to protect, and keeping it would read as if the collision still existed.
                entries_enabled = EXCLUDED.entries_enabled
            """,
            batch, java.sql.Date.valueOf(runDate), pass.name(), strategies, candidates,
            entries, exits, exitSkipped, openAtStart, wouldEnter, admitted, capExceedance, capBound,
            writeDropped(droppedByCap), pass.takesEntries());
    return rows > 0;
  }

  /**
   * WHICH pass wrote a row — the discriminator V063 added to the primary key (ledger H23), DECLARED
   * by the call site. {@link #name()} IS the {@code pass} column value, and V063's CHECK constraint
   * pins the same three strings, so adding a constant here without a migration fails the INSERT
   * rather than writing an unreadable label.
   *
   * <p>⚠️ <b>Declared, never derived.</b> The first cut derived this from {@code entriesEnabled},
   * and two of the three origins run with entries disabled — so {@link #SETTLE} and {@link
   * #RECOVERY_EXITS} collapsed onto one key and the recovery pass kept destroying the settle's row,
   * which is the whole defect V063 exists to fix. The rule it encodes: a discriminator must record
   * WHERE a run came from, not what it happened to do, because two different origins can do the
   * same thing.
   */
  public enum Pass {
    /**
     * A run that took ENTRIES: the 08:35 catch-up on a session whose own screen has landed, or a
     * manual {@code POST /api/v1/signals/<batch>-swing/run}.
     *
     * <p>The manual path folds in here deliberately rather than taking a fourth constant. {@link
     * SwingBatchRunRepository#hasRunWithEntries} is a money gate — it is what stops the catch-up
     * re-entering names already on the book — and a separate MANUAL value would have to be
     * remembered in its predicate (and in {@link SwingBatchRunRepository#recentProbes}, and in
     * {@code SwingCatchUpStateRepository.seedMissing}) forever, where forgetting it double-enters a
     * session.
     *
     * <p>⚠️ <b>The tempting justification is FALSE, so do not rest on it:</b> "a manual run
     * stamps TODAY while the catch-up only ever reaches a PAST session". {@code sessionWindow}
     * bounds only the {@code seedWindow} call ({@code SwingBatchCatchUp:307}); {@code
     * pendingSessions} carries no date bound at all ({@code SwingPaperEffectRepository:402-408}),
     * {@code seedPending} applies no run-marker gate ({@code SwingBatchCatchUp:309-311}), and the
     * sweep then iterates EVERY retryable row ({@code :447-451}). The catch-up CAN reach today, so
     * a test asserting otherwise would pin a falsehood.
     *
     * <p>The correct argument needs none of that and holds in all three orders. If the manual run
     * recorded its marker it already OWNS today's ENTRIES key, and {@code hasRunWithEntries}
     * short-circuits the catch-up at {@code SwingBatchCatchUp:490} before it can write. If that
     * marker write fail-softed there is no key to overwrite. And if the catch-up gets there first,
     * a later manual run re-stamps the SAME key — two genuinely entries-taking runs on one
     * session, which is the documented same-date re-stamp, not a cross-origin loss.
     */
    ENTRIES(true),
    /** The scheduled evening exits-only settle (18:52 minervini / 18:53 manas-arora). */
    SETTLE(false),
    /**
     * The catch-up's exits-only RECOVERY: the arming is authoritative but the funnel is not this
     * session's screen, so the exits ran and the entries were withheld ({@code
     * SwingBatchCatchUp:643-645}, reason {@code SCREEN_NOT_AS_OF_SESSION}). Distinct from {@link
     * #SETTLE} because that branch leaves the session RETRYABLE — sharing SETTLE's key, it would
     * overwrite the evening settle's counters on every subsequent sweep.
     */
    RECOVERY_EXITS(false);

    private final boolean takesEntries;

    Pass(boolean takesEntries) {
      this.takesEntries = takesEntries;
    }

    /** Whether this pass runs the ENTRY half — and so the value written to {@code entries_enabled}. */
    public boolean takesEntries() {
      return takesEntries;
    }
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
   * <p>A pre-V060 row has {@code entries_enabled} NULL, and that rule now lives in V063's backfill
   * rather than in this query: every historical row was written by a full 20:00 batch that did both
   * halves, so it backfills to {@code ENTRIES}. Treating those as owing entries would hand the
   * catch-up every past session at once.
   *
   * <p>⚠️ Reads {@code pass}, so it excludes BOTH exits-flavoured passes — {@link Pass#SETTLE} and
   * {@link Pass#RECOVERY_EXITS}. Under the derived form it could not have: the recovery row simply
   * overwrote the settle row and there was only ever one to exclude.
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
        -- property of the entry pass; an exits-only pass writes would_enter = 0 (not null), so
        -- before the key split it was indistinguishable here, and after the split up to THREE rows
        -- exist for the same date (ENTRIES + SETTLE + RECOVERY_EXITS) — without this predicate a
        -- LIMIT n would silently return as little as a third as many probed sessions as it used to,
        -- padded with exits rows carrying zeros.
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
