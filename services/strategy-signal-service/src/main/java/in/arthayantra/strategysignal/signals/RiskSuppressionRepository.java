package in.arthayantra.strategysignal.signals;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
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

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public RiskSuppressionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
