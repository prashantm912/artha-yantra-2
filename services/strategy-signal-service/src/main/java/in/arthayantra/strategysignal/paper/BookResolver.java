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
}
