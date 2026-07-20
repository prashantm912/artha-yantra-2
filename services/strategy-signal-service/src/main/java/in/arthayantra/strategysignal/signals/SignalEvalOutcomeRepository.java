package in.arthayantra.strategysignal.signals;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code strategy.signal_eval_outcomes} (V043) — the durable 3m rollup of the
 * SignalEngine entry-evaluation outcome counters. Those counters are in-memory Micrometer meters
 * that reset on every restart, so before this table "was the engine alive on date X?" was
 * unanswerable once the process bounced; that ambiguity produced two false starvation diagnoses
 * (2026-07-17 and 2026-07-20, the latter costing an unnecessary restart of a live trading service).
 *
 * <p><b>The checkpoint lives here, not in the JVM.</b> {@link #lastCumulativeForBoot} is what makes
 * the delta protocol idempotent: the writer differences the live counters against the newest
 * DURABLE row rather than against process memory, so a lost acknowledgement cannot double-count and
 * a failed write followed by a restart cannot lose. See the V043 header for the full protocol.
 *
 * <p>Written ONLY by {@link SignalEvalOutcomeRollupJob}, on its own dedicated scheduler thread —
 * never from the signal-eval thread. OBSERVABILITY ONLY: no trading decision reads this table.
 */
@Repository
public class SignalEvalOutcomeRepository {

  /**
   * Query timeout for every statement here. The rollup owns an expendable single-thread scheduler,
   * so a stall parks nothing load-bearing — but an UNBOUNDED wait would still hold that thread
   * forever and silently end the liveness record. Generous relative to the work (a 7-row upsert and
   * a 7-row indexed read against local Postgres), tight relative to the 3-minute tick, so a stuck
   * statement always clears well before the next one is due.
   */
  private static final int QUERY_TIMEOUT_SECONDS = 20;

  private final JdbcTemplate jdbc;

  /**
   * Wires the strategy datasource behind a PRIVATE {@link JdbcTemplate} carrying the query timeout.
   * A dedicated template rather than the shared bean on purpose: {@code setQueryTimeout} mutates
   * template state, so applying it to the injected singleton would impose this timeout on every
   * unrelated caller in the service.
   */
  public SignalEvalOutcomeRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  /**
   * The DURABLE delta checkpoint for one counter epoch: the cumulative value per outcome, and WHEN
   * it was taken.
   *
   * <p>{@code bucketTime} is load-bearing, not diagnostics. The delta a tick writes covers
   * {@code (bucketTime, now]}, so if that span crosses an IST session date the evaluations inside it
   * cannot be attributed to either date — see {@code SignalEvalOutcomeRollupJob.crossDateOrigin}.
   * Without it the whole recovered window silently lands on today.
   *
   * @param bucketTime the newest persisted bucket for this boot, or {@code null} if it has none yet
   * @param cumulative counter value per outcome tag at that bucket; empty alongside a null bucket
   */
  public record Checkpoint(OffsetDateTime bucketTime, Map<String, Long> cumulative) {

    /** True when this boot has never written — its first tick differences against zero. */
    public boolean absent() {
      return bucketTime == null;
    }
  }

  /**
   * The newest persisted checkpoint for {@code bootId}.
   *
   * <p>Absent for a boot that has not yet written (its first tick therefore differences against 0
   * and records everything since boot — the correct restart semantics). {@code DISTINCT ON} takes
   * one row per outcome ordered by {@code bucket_time DESC}, served by
   * {@code idx_signal_eval_outcomes_boot}. All seven outcomes are written in ONE statement so they
   * always share a bucket, but the max is taken rather than assumed.
   */
  public Checkpoint lastCumulativeForBoot(UUID bootId) {
    // A two-arg RowMapper, not a RowCallbackHandler lambda: the single-arg form is ambiguous
    // against JdbcTemplate's ResultSetExtractor overload and will not compile.
    List<Object[]> rows =
        jdbc.query(
            """
            SELECT DISTINCT ON (outcome) outcome, cumulative_count, bucket_time
              FROM signal_eval_outcomes
             WHERE boot_id = ?
             ORDER BY outcome, bucket_time DESC
            """,
            (rs, rowNum) ->
                new Object[] {
                  rs.getString("outcome"),
                  rs.getLong("cumulative_count"),
                  rs.getObject("bucket_time", OffsetDateTime.class)
                },
            bootId);
    Map<String, Long> cumulative = new HashMap<>();
    OffsetDateTime newest = null;
    for (Object[] row : rows) {
      cumulative.put((String) row[0], (Long) row[1]);
      OffsetDateTime bucket = (OffsetDateTime) row[2];
      if (newest == null || bucket.isAfter(newest)) {
        newest = bucket;
      }
    }
    return new Checkpoint(newest, cumulative);
  }

  /**
   * Persists one bucket's per-outcome deltas and the cumulative values they were derived from, as a
   * SINGLE statement.
   *
   * <p>One multi-row {@code INSERT} rather than a {@code batchUpdate} deliberately: a batch is not
   * atomic outside a transaction, so a partial failure could leave a subset of outcomes advanced and
   * the rest not, splitting the checkpoint across outcomes. One statement is all-or-nothing, so the
   * checkpoint moves for all seven or for none.
   *
   * <p>On conflict {@code eval_count} ADDS while {@code cumulative_count} REPLACES. Addition is safe
   * precisely because the deltas were computed against the durable checkpoint: a second write into
   * the same {@code (bucket, boot)} is always a genuine further increment, never a replay of counts
   * already recorded. Two instances overlapping in a blue/green restart never collide here at all —
   * different {@code boot_id}, different rows.
   *
   * @param bucketTime the snapshot instant floored to a 3m boundary
   * @param bootId the counter epoch these values belong to
   * @param deltas per-outcome evaluations for the window ending at {@code bucketTime}; zeros are
   *     included on purpose (a zero row proves the process was alive)
   * @param cumulative per-outcome counter values at this snapshot — the next tick's checkpoint
   * @param recoveredFrom non-null ONLY when this delta's window crosses an IST session date, in
   *     which case it is the originating checkpoint's bucket and these counts are UNATTRIBUTABLE to
   *     any single date (see the V043 header); null on every normal tick
   * @return the number of rows inserted or merged
   */
  public int upsertBucket(
      OffsetDateTime bucketTime,
      UUID bootId,
      Map<String, Long> deltas,
      Map<String, Long> cumulative,
      OffsetDateTime recoveredFrom) {
    if (deltas.isEmpty()) {
      return 0;
    }
    List<Object> args = new ArrayList<>(deltas.size() * 6);
    deltas.forEach(
        (outcome, delta) -> {
          args.add(bucketTime);
          args.add(bootId);
          args.add(outcome);
          args.add(delta);
          args.add(cumulative.getOrDefault(outcome, 0L));
          args.add(recoveredFrom);
        });
    String values = String.join(",", Collections.nCopies(deltas.size(), "(?, ?, ?, ?, ?, ?)"));
    return jdbc.update(
        "INSERT INTO signal_eval_outcomes"
            + " (bucket_time, boot_id, outcome, eval_count, cumulative_count, recovered_from)"
            + " VALUES "
            + values
            + " ON CONFLICT (bucket_time, boot_id, outcome) DO UPDATE SET"
            + " eval_count = signal_eval_outcomes.eval_count + EXCLUDED.eval_count,"
            + " cumulative_count = EXCLUDED.cumulative_count,"
            // Once a bucket holds an unattributable recovery it stays marked: COALESCE keeps the
            // EARLIEST origin so the recorded span never silently narrows.
            + " recovered_from = LEAST("
            + "COALESCE(signal_eval_outcomes.recovered_from, EXCLUDED.recovered_from),"
            + " COALESCE(EXCLUDED.recovered_from, signal_eval_outcomes.recovered_from))",
        args.toArray());
  }

  /**
   * Bounded-retention prune: deletes buckets older than {@code days} days. The cutoff is computed
   * SERVER-SIDE ({@code now() - make_interval}) on the {@code timestamptz} column, so it is
   * timezone-correct regardless of the container's UTC display clock — the UTC-vs-IST trap bites
   * {@code ::date} truncation and rendering, not interval arithmetic on an absolute instant. Runs as
   * the {@code artha} owner, which may DELETE (the V043 grant gives {@code ay_strategy} SELECT only).
   *
   * <p>Cannot orphan a running boot's checkpoint: a live boot rewrites a row every 3 minutes, so its
   * newest row — the only one {@link #lastCumulativeForBoot} reads — is always far inside a 180-day
   * horizon.
   *
   * @return the number of rows deleted
   */
  public int deleteOlderThanDays(int days) {
    return jdbc.update(
        "DELETE FROM signal_eval_outcomes WHERE bucket_time < now() - make_interval(days => ?)",
        days);
  }
}
