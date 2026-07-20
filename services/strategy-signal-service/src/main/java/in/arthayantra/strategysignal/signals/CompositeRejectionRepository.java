package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code composite_rejections} (V044) — every entry evaluation whose chart gates
 * passed but whose A1 composite came in under {@code entry_rules.scoring.threshold}. INSERT-only
 * from the live engine, SELECT for analysis, plus the bounded-retention prune.
 *
 * <p>Deliberately a SEPARATE table from {@code signal_rejections} rather than a new
 * {@code blocking_rail} on it — see the V044 header. That table's consumers all assume the §12.3
 * confluence diagnostic shape, and sharing it forced a filter into {@link DotHealthCanary}'s query
 * to stop it false-paging.
 */
@Repository
public class CompositeRejectionRepository {

  /** One composite-rejection row. */
  public record CompositeRejectionRow(
      long id,
      UUID strategyVersionId,
      String strategySlug,
      String exchange,
      String tradingsymbol,
      String interval,
      BigDecimal composite,
      BigDecimal threshold,
      BigDecimal margin,
      JsonNode scoreBreakdown,
      OffsetDateTime barTime,
      OffsetDateTime generatedAt) {}

  /**
   * Query timeout for the retention DELETE only. The prune owns an expendable scheduler thread, so a
   * stall parks nothing load-bearing — but an UNBOUNDED lock wait would hold that thread and one of
   * the five Hikari connections for the whole session. Generous relative to the work (~192
   * rows/session, so ~35k rows at the 180-day horizon) against local Postgres, so a legitimate prune
   * never trips it; a tripped one is fail-soft, alerted, and retried by the next daily tick.
   */
  private static final int PRUNE_TIMEOUT_SECONDS = 60;

  private final JdbcTemplate jdbc;
  private final JdbcTemplate pruneJdbc;
  private final ObjectMapper objectMapper;

  /**
   * Wires the strategy datasource. The prune gets its OWN {@link JdbcTemplate} over the same
   * DataSource because {@code setQueryTimeout} mutates template state — applying it to the shared
   * injected bean would impose this timeout on every unrelated caller in the service.
   */
  public CompositeRejectionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.pruneJdbc =
        new JdbcTemplate(
            Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate has no DataSource"));
    this.pruneJdbc.setQueryTimeout(PRUNE_TIMEOUT_SECONDS);
    this.objectMapper = objectMapper;
  }

  /** Persists one composite rejection; returns its id. */
  public long insert(
      UUID strategyVersionId,
      String strategySlug,
      String exchange,
      String tradingsymbol,
      String interval,
      BigDecimal composite,
      BigDecimal threshold,
      BigDecimal margin,
      String scoreBreakdownJson,
      OffsetDateTime barTime) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO composite_rejections
              (strategy_version_id, strategy_slug, exchange, tradingsymbol, "interval",
               composite, threshold, margin, score_breakdown, bar_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id
            """,
            Long.class,
            strategyVersionId, strategySlug, exchange, tradingsymbol, interval,
            composite, threshold, margin, scoreBreakdownJson, barTime);
    return id == null ? -1 : id;
  }

  /** Paged history with optional filters (newest first). */
  public List<CompositeRejectionRow> list(
      UUID strategyVersionId, OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM composite_rejections WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (strategyVersionId != null) {
      sql.append(" AND strategy_version_id = ?");
      args.add(strategyVersionId);
    }
    if (from != null) {
      sql.append(" AND generated_at >= ?");
      args.add(from);
    }
    if (to != null) {
      sql.append(" AND generated_at < ?");
      args.add(to);
    }
    sql.append(" ORDER BY generated_at DESC, id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::row, args.toArray());
  }

  /**
   * Bounded-retention prune: deletes rows whose {@code generated_at} is older than {@code days}.
   * Plain row-wise DELETE — this is an OLTP table, not a hypertable, and the volume is trivial
   * (~192 rows/session). Runs as {@code artha}; the {@code ay_strategy} grant is append-only.
   *
   * <p>Issued on the private {@link #pruneJdbc} so it is BOUNDED by {@link #PRUNE_TIMEOUT_SECONDS}:
   * a lock wait must not pin a scheduler thread and a connection indefinitely.
   *
   * @return the number of rows deleted
   */
  public int deleteOlderThanDays(int days) {
    return pruneJdbc.update(
        "DELETE FROM composite_rejections WHERE generated_at < now() - make_interval(days => ?)",
        days);
  }

  private CompositeRejectionRow row(ResultSet rs, int rowNum) throws SQLException {
    return new CompositeRejectionRow(
        rs.getLong("id"),
        rs.getObject("strategy_version_id", UUID.class),
        rs.getString("strategy_slug"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("interval"),
        rs.getBigDecimal("composite"),
        rs.getBigDecimal("threshold"),
        rs.getBigDecimal("margin"),
        readTree(rs.getString("score_breakdown")),
        rs.getObject("bar_time", OffsetDateTime.class),
        rs.getObject("generated_at", OffsetDateTime.class));
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored score_breakdown is not valid JSON", e);
    }
  }
}
