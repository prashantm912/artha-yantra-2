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
 * one row per IST run date; {@code SwingBatchCanary} checks the exact historical session next
 * morning and alerts when a schedule-time armed batch has no matching row.
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
      boolean capBound, List<DroppedCandidate> droppedByCap) {
    int rows =
        jdbc.update(
            """
            INSERT INTO swing_batch_runs
                (batch, run_date, ran_at, strategies, candidates, entries, exits, exit_skipped,
                 open_at_start, would_enter, admitted, cap_exceedance, cap_bound, dropped_by_cap)
            VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (batch, run_date) DO UPDATE SET
                ran_at = now(), strategies = EXCLUDED.strategies, candidates = EXCLUDED.candidates,
                entries = EXCLUDED.entries, exits = EXCLUDED.exits,
                exit_skipped = EXCLUDED.exit_skipped, open_at_start = EXCLUDED.open_at_start,
                would_enter = EXCLUDED.would_enter, admitted = EXCLUDED.admitted,
                cap_exceedance = EXCLUDED.cap_exceedance, cap_bound = EXCLUDED.cap_bound,
                dropped_by_cap = EXCLUDED.dropped_by_cap
            """,
            batch, java.sql.Date.valueOf(runDate), strategies, candidates, entries, exits, exitSkipped,
            openAtStart, wouldEnter, admitted, capExceedance, capBound, writeDropped(droppedByCap));
    return rows > 0;
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
   * The most recent probed runs for a batch, newest first (ledger F3). Only rows carrying a probe
   * (post-V034 armed runs) are returned — a pre-migration / no-op row reads as "not probed".
   */
  public List<ProbeRow> recentProbes(String batch, int limit) {
    return jdbc.query(
        """
        SELECT batch, run_date, ran_at, candidates, entries, open_at_start, would_enter, admitted,
               cap_exceedance, cap_bound, dropped_by_cap
        FROM swing_batch_runs
        WHERE batch = ? AND would_enter IS NOT NULL
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
