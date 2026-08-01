package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategysignal.signals.SignalEngine.StrategyEvalKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code strategy.strategy_eval_denominator} (V053) — the per-strategy, per-IST-session
 * evaluation counts that give every per-strategy support-rate and pass-rate a real denominator.
 *
 * <p><b>Cumulative values, REPLACED — deliberately not V045's delta protocol.</b> {@link
 * #upsertCounts} writes each key's absolute in-memory total and the {@code ON CONFLICT} assigns it.
 * Nothing on the database side adds, so re-running a flush cannot accumulate: an identical flush
 * writes an identical number, and a later flush writes the correct larger one. That removes the two
 * failure modes V045 needs a durable checkpoint for — a lost acknowledgement re-writes the same
 * absolute value instead of re-covering a window, and a failed write leaves the adder untouched so
 * the next flush carries the whole gap. There is no checkpoint read here at all.
 *
 * <p><b>A mid-day restart cannot corrupt a day.</b> {@code boot_id} is part of the primary key, so a
 * restarted process (fresh {@code SignalEngine.counterEpoch}, zeroed adders) writes NEW rows rather
 * than overwriting the earlier boot's larger ones. A day's true total is {@code SUM(eval_count)}
 * across boots.
 *
 * <p>Written ONLY by {@link SignalEvalOutcomeRollupJob}, on the existing V045 scheduler thread —
 * never from the signal-eval thread. OBSERVABILITY ONLY: no trading decision reads this table.
 */
@Repository
public class StrategyEvalDenominatorRepository {

  /**
   * Query timeout for every statement here, matching {@link SignalEvalOutcomeRepository}. The flush
   * shares that job's expendable single-thread scheduler, so a stall parks nothing load-bearing — but
   * an UNBOUNDED wait would still hold the thread forever and silently end both records. Generous
   * relative to the work (at most a few hundred narrow rows against local Postgres), tight relative
   * to the 3-minute tick.
   */
  private static final int QUERY_TIMEOUT_SECONDS = 20;

  private final JdbcTemplate jdbc;

  /**
   * Wires the strategy datasource behind a PRIVATE {@link JdbcTemplate} carrying the query timeout —
   * a dedicated template rather than the shared bean because {@code setQueryTimeout} mutates template
   * state and would otherwise impose this timeout on every unrelated caller in the service.
   */
  public StrategyEvalDenominatorRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  /**
   * Persists one snapshot of the per-strategy counters as a SINGLE multi-row upsert.
   *
   * <p>One statement rather than a {@code batchUpdate} for the same reason as V045: a batch is not
   * atomic outside a transaction, so a partial failure could advance some keys and not others. Here
   * that would only cost freshness rather than correctness (every value is absolute), but one
   * statement is also one round trip, so there is nothing to trade away.
   *
   * <p>{@code DO UPDATE SET eval_count = EXCLUDED.eval_count} is the whole idempotency argument: the
   * value is a pure function of one in-memory adder and the row is assigned it, so a double flush is
   * a no-op by construction rather than by a guard.
   *
   * @param bootId the counter epoch these totals belong to
   * @param counts every observed (session date, slug, outcome) and its CUMULATIVE evaluation count
   * @return the number of rows inserted or merged
   */
  public int upsertCounts(UUID bootId, Map<StrategyEvalKey, Long> counts) {
    if (counts.isEmpty()) {
      return 0;
    }
    List<Object> args = new ArrayList<>(counts.size() * 5);
    counts.forEach(
        (key, count) -> {
          args.add(key.sessionDate());
          args.add(bootId);
          args.add(key.slug());
          args.add(key.outcome().tag());
          args.add(count);
        });
    String values = String.join(",", Collections.nCopies(counts.size(), "(?, ?, ?, ?, ?)"));
    return jdbc.update(
        "INSERT INTO strategy_eval_denominator"
            + " (session_date, boot_id, strategy_slug, outcome, eval_count)"
            + " VALUES "
            + values
            + " ON CONFLICT (session_date, boot_id, strategy_slug, outcome) DO UPDATE SET"
            + " eval_count = EXCLUDED.eval_count",
        args.toArray());
  }

  /**
   * Bounded-retention prune: deletes sessions older than {@code days} days. The cutoff is computed
   * SERVER-SIDE on a DATE column, so there is no timestamptz rendering to get wrong.
   *
   * <p>Unlike V045 this cannot orphan anything a live boot depends on: the writer keeps no checkpoint
   * in the table, so deleting a row can only ever cost history.
   *
   * @return the number of rows deleted
   */
  public int deleteOlderThanDays(int days) {
    return jdbc.update(
        "DELETE FROM strategy_eval_denominator"
            + " WHERE session_date < (now() - make_interval(days => ?))::date",
        days);
  }
}
