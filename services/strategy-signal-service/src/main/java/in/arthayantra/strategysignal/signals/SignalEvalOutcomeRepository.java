package in.arthayantra.strategysignal.signals;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code strategy.signal_eval_outcomes} (V043) — the durable 3m rollup of the
 * SignalEngine entry-evaluation outcome counters. Those counters are in-memory Micrometer meters
 * that reset on every restart, so before this table "was the engine alive on date X?" was
 * unanswerable once the process bounced; that ambiguity produced two false starvation diagnoses
 * (2026-07-17 and 2026-07-20, the latter costing an unnecessary restart of a live trading service).
 *
 * <p>Written ONLY by {@link SignalEvalOutcomeRollupJob} on the scheduler thread — never from the
 * signal-eval thread. OBSERVABILITY ONLY: no trading decision reads this table.
 */
@Repository
public class SignalEvalOutcomeRepository {

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public SignalEvalOutcomeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Persists one bucket's per-outcome DELTAS as a SINGLE statement.
   *
   * <p>One multi-row {@code INSERT} rather than a {@code batchUpdate} deliberately: a batch is not
   * atomic outside a transaction, so a partial failure would write some outcomes, leave the caller's
   * baseline un-advanced, and then DOUBLE-COUNT the already-written rows on the next tick via the
   * additive {@code ON CONFLICT}. One statement is all-or-nothing, so the caller's
   * "advance the baseline only after a durable write" rule stays sound with no transaction
   * machinery.
   *
   * <p>The conflict merge ADDS rather than replaces. A single instance cannot re-fire within a
   * bucket (one scheduler thread), but a blue/green restart overlap can have two instances both
   * genuinely evaluating the same bucket — addition is the delta-correct merge for that.
   *
   * @param bucketTime the snapshot instant floored to a 3m boundary
   * @param deltasByOutcome per-outcome evaluation counts for the window ending at {@code bucketTime};
   *     zeros are included on purpose (a zero row proves the process was alive)
   * @return the number of rows inserted or merged
   */
  public int upsertBucket(OffsetDateTime bucketTime, Map<String, Long> deltasByOutcome) {
    if (deltasByOutcome.isEmpty()) {
      return 0;
    }
    List<Object> args = new ArrayList<>(deltasByOutcome.size() * 3);
    deltasByOutcome.forEach(
        (outcome, count) -> {
          args.add(bucketTime);
          args.add(outcome);
          args.add(count);
        });
    String values = String.join(",", Collections.nCopies(deltasByOutcome.size(), "(?, ?, ?)"));
    return jdbc.update(
        "INSERT INTO signal_eval_outcomes (bucket_time, outcome, eval_count) VALUES "
            + values
            + " ON CONFLICT (bucket_time, outcome) DO UPDATE SET"
            + " eval_count = signal_eval_outcomes.eval_count + EXCLUDED.eval_count",
        args.toArray());
  }

  /**
   * Bounded-retention prune: deletes buckets older than {@code days} days. The cutoff is computed
   * SERVER-SIDE ({@code now() - make_interval}) on the {@code timestamptz} column, so it is
   * timezone-correct regardless of the container's UTC display clock — the UTC-vs-IST trap bites
   * {@code ::date} truncation and rendering, not interval arithmetic on an absolute instant. Runs as
   * the {@code artha} owner, which may DELETE (the V043 grant gives {@code ay_strategy} SELECT only).
   *
   * @return the number of rows deleted
   */
  public int deleteOlderThanDays(int days) {
    return jdbc.update(
        "DELETE FROM signal_eval_outcomes WHERE bucket_time < now() - make_interval(days => ?)",
        days);
  }
}
