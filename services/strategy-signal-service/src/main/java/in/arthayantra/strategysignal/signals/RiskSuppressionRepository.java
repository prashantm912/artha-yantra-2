package in.arthayantra.strategysignal.signals;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code risk_suppressions} table (PF-03) — the live-only durable record of an
 * ENTRY signal CENSORED by the per-book paper risk governor (kill switch / MAX_OPEN concurrency cap /
 * daily-loss trip / daily-profit target / max deployment / F9 heat cap). INSERT-only from the live
 * engine ({@code SignalEngine.emitEntry}'s veto branch); the golden replay injects no
 * {@link EmissionGuard} so no rows are written on backtest. OBSERVABILITY ONLY — writing a row never
 * changes the veto decision.
 */
@Repository
public class RiskSuppressionRepository {

  /** One risk-suppression row. */
  public record RiskSuppressionRow(
      long id,
      UUID strategyVersionId,
      String strategySlug,
      String book,
      String rail,
      String exchange,
      String tradingsymbol,
      String interval,
      String side,
      String optionType,
      String optionTradingsymbol,
      OffsetDateTime barTime,
      OffsetDateTime generatedAt) {}

  /**
   * Query timeout for the retention DELETE only. The prune owns an expendable scheduler thread, so a
   * stall parks nothing load-bearing — but an UNBOUNDED lock wait would hold that thread and one of
   * the five Hikari connections for the whole session. Generous relative to the work (a row-wise
   * delete over a table whose worst case is ~2-3k rows/day against local Postgres), so a legitimate
   * prune never trips it; a tripped one is fail-soft, alerted, and retried by the next daily tick.
   */
  private static final int PRUNE_TIMEOUT_SECONDS = 60;

  private final JdbcTemplate jdbc;
  private final JdbcTemplate pruneJdbc;

  /**
   * Wires the strategy datasource. The prune gets its OWN {@link JdbcTemplate} over the same
   * DataSource because {@code setQueryTimeout} mutates template state — applying it to the shared
   * injected bean would impose this timeout on every unrelated caller in the service.
   */
  public RiskSuppressionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.pruneJdbc =
        new JdbcTemplate(
            Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate has no DataSource"));
    this.pruneJdbc.setQueryTimeout(PRUNE_TIMEOUT_SECONDS);
  }

  /** Persists one risk suppression; returns its id. */
  public long insert(
      UUID strategyVersionId,
      String strategySlug,
      String book,
      String rail,
      String exchange,
      String tradingsymbol,
      String interval,
      String side,
      String optionType,
      String optionTradingsymbol,
      OffsetDateTime barTime) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO risk_suppressions
              (strategy_version_id, strategy_slug, book, rail, exchange, tradingsymbol, "interval",
               side, option_type, option_tradingsymbol, bar_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """,
            Long.class,
            strategyVersionId, strategySlug, book, rail, exchange, tradingsymbol, interval,
            side, optionType, optionTradingsymbol, barTime);
    return id == null ? -1 : id;
  }

  /**
   * Bounded-retention prune (task_f168c23c): deletes rows whose {@code generated_at} is older than
   * {@code days} days. The cutoff is computed SERVER-SIDE ({@code now() - make_interval}) on the
   * {@code timestamptz} column, so it is timezone-correct regardless of the container's UTC display
   * clock — no host/IST skew (the UTC-vs-IST trap only bites {@code ::date} truncation and rendering,
   * not interval arithmetic on an absolute instant). Runs as the {@code artha} owner role, which may
   * DELETE (the append-only SELECT+INSERT grant restricts only {@code ay_strategy}).
   *
   * <p>Issued on the private {@link #pruneJdbc} so it is BOUNDED by {@link #PRUNE_TIMEOUT_SECONDS}:
   * a lock wait must not pin a scheduler thread and a connection indefinitely.
   *
   * @return the number of rows deleted
   */
  public int deleteOlderThanDays(int days) {
    return pruneJdbc.update(
        "DELETE FROM risk_suppressions WHERE generated_at < now() - make_interval(days => ?)", days);
  }

  /** Paged history with optional filters (newest first). */
  public List<RiskSuppressionRow> list(
      UUID strategyVersionId, String rail, String book, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM risk_suppressions WHERE 1=1");
    List<Object> args = new java.util.ArrayList<>();
    if (strategyVersionId != null) {
      sql.append(" AND strategy_version_id = ?");
      args.add(strategyVersionId);
    }
    if (rail != null) {
      sql.append(" AND rail = ?");
      args.add(rail);
    }
    if (book != null) {
      sql.append(" AND book = ?");
      args.add(book);
    }
    sql.append(" ORDER BY generated_at DESC, id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  private RiskSuppressionRow row(ResultSet rs, int rowNum) throws SQLException {
    return new RiskSuppressionRow(
        rs.getLong("id"),
        rs.getObject("strategy_version_id", UUID.class),
        rs.getString("strategy_slug"),
        rs.getString("book"),
        rs.getString("rail"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("interval"),
        rs.getString("side"),
        rs.getString("option_type"),
        rs.getString("option_tradingsymbol"),
        rs.getObject("bar_time", OffsetDateTime.class),
        rs.getObject("generated_at", OffsetDateTime.class));
  }
}
