package in.arthayantra.strategysignal.paper;

import in.arthayantra.strategysignal.signals.Books;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves a paper BOOK — the per-strategy-family capital bucket a signal/position belongs to
 * ('scalper' · 'minervini' · 'manas-arora' · 'manual' · 'other'). The family is the strategy's first
 * recognised {@code strategies.tags} entry (see {@link Books}); a hand order with no signal is
 * {@code MANUAL}. Used to stamp {@code paper_positions.book} at open + pick the per-book capital/risk.
 */
@Component
public class BookResolver {

  /** The known book names (re-exported from {@link Books} for the paper module). */
  public static final String SCALPER = Books.SCALPER;

  public static final String MINERVINI = Books.MINERVINI;
  public static final String MANAS_ARORA = Books.MANAS_ARORA;
  public static final String MANUAL = Books.MANUAL;
  public static final String OTHER = Books.OTHER;

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public BookResolver(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The book for a family tag set — the first recognised family tag, else {@code OTHER}. */
  public static String fromTags(Collection<String> tags) {
    return Books.fromTags(tags);
  }

  /**
   * The book that owns a signal — read from the {@code signals.book} column stamped at emission (T1),
   * frozen against later tag drift. A missing signal or a null book resolves to {@code OTHER}; callers
   * with no signal use {@code MANUAL} directly.
   */
  public String bookForSignal(long signalId) {
    return jdbc
        .query(
            "SELECT book FROM signals WHERE id = ?",
            (rs, n) -> rs.getString("book"),
            signalId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(OTHER);
  }

  /**
   * The STRATEGY (not the version) a signal belongs to — the V058 strategy-scoped position key
   * (option D). Empty for a missing signal or a version with no strategy.
   *
   * <p>The strategy, deliberately, never the {@code strategy_version_id}: a republish mints a new
   * version row, so a version-keyed position key would refuse to average a genuine same-strategy
   * pyramid add across a hot-swap and would open a second row instead. {@code strategies.id} is
   * stable across every publish.
   */
  public java.util.Optional<java.util.UUID> strategyIdForSignal(long signalId) {
    return jdbc
        .query(
            """
            SELECT sv.strategy_id
            FROM signals s
            JOIN strategy_versions sv ON sv.id = s.strategy_version_id
            WHERE s.id = ?
            """,
            (rs, n) -> rs.getObject("strategy_id", java.util.UUID.class),
            signalId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }
}
