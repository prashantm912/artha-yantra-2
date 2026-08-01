package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to the {@code signal_rejections} table — the live-only diagnostics of WHY a scalper
 * chart-entry was blocked by the §12.3 confluence gate. Read-only from the UI; INSERT-only from the
 * live engine (the golden replay never reaches the gate, so no rows are written on backtest).
 */
@Repository
public class SignalRejectionRepository {

  /**
   * One rejection row — also the wire shape of {@code GET /api/v1/signal-rejections}'s items
   * (ledger D3 slice 1), which is why the nullable columns carry {@code @Schema} here.
   *
   * <p>The nullable set is {@code V015__signal_rejections.sql}'s: {@code side} (NULL when the block
   * landed before the side resolved, or on a neutral straddle), the four {@code blocking_*}
   * diagnostics, and the two {@code composite_*} values (NULL when the rail fired before the
   * composite was computed). Spelled {@code types = {"x", "null"}} because {@code nullable = true}
   * is a SILENT NO-OP at OpenAPI 3.1. Response-only — no request body binds this record, so marking
   * the rest required cannot mislead a client into sending them.
   */
  public record RejectionRow(
      long id,
      UUID strategyVersionId,
      String strategySlug,
      String exchange,
      String tradingsymbol,
      String interval,
      @Schema(types = {"string", "null"}) String side,
      String blockingRail,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal blockingOperand,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal blockingThreshold,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal blockingMargin,
      @Schema(types = {"string", "null"}) String blockingReason,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal compositeScore,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal compositeThreshold,
      JsonNode diagnostic,
      OffsetDateTime barTime,
      OffsetDateTime generatedAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires the strategy datasource. */
  public SignalRejectionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Persists one rejection; returns its id. */
  public long insert(
      UUID strategyVersionId,
      String strategySlug,
      String exchange,
      String tradingsymbol,
      String interval,
      String side,
      String blockingRail,
      BigDecimal blockingOperand,
      BigDecimal blockingThreshold,
      BigDecimal blockingMargin,
      String blockingReason,
      BigDecimal compositeScore,
      BigDecimal compositeThreshold,
      String diagnosticJson,
      OffsetDateTime barTime) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO signal_rejections
              (strategy_version_id, strategy_slug, exchange, tradingsymbol, "interval", side,
               blocking_rail, blocking_operand, blocking_threshold, blocking_margin, blocking_reason,
               composite_score, composite_threshold, diagnostic, bar_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id
            """,
            Long.class,
            strategyVersionId, strategySlug, exchange, tradingsymbol, interval, side,
            blockingRail, blockingOperand, blockingThreshold, blockingMargin, blockingReason,
            compositeScore, compositeThreshold, diagnosticJson, barTime);
    return id == null ? -1 : id;
  }

  /** Paged history with optional filters (newest first). */
  public List<RejectionRow> list(
      UUID strategyVersionId, String blockingRail, String exchange, String tradingsymbol,
      OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM signal_rejections WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (strategyVersionId != null) {
      sql.append(" AND strategy_version_id = ?");
      args.add(strategyVersionId);
    }
    if (blockingRail != null) {
      sql.append(" AND blocking_rail = ?");
      args.add(blockingRail);
    }
    if (exchange != null && tradingsymbol != null) {
      sql.append(" AND exchange = ? AND tradingsymbol = ?");
      args.add(exchange);
      args.add(tradingsymbol);
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
   * One (bar, side) group's representative diagnostic — the G16 near-miss evidence unit.
   *
   * <p>A rejection row is per STRATEGY, but the near-miss operands are market-wide scalars scored
   * per side, so 38 scalpers on one bar produce 38 rows carrying the same verdict. Counting rows
   * would let fan-out swamp the tally; the (bar, side) pair is the independent observation.
   */
  public record BarSideDiagnostic(OffsetDateTime barTime, String side, JsonNode diagnostic) {}

  /**
   * ONE representative confluence-bearing diagnostic per DISTINCT {@code (bar_time, side)} over a
   * bounded window — the session-wide evidence the G16 near-miss probe reads (the paged {@link
   * #list} tail cannot answer a session-wide question: under fan-out its newest-N rows can cover a
   * handful of bars, so an early crossing ages out and the dot reads never-crossing later the same
   * session).
   *
   * <p>Callers pass an IST-day bound, so the {@code generated_at DESC} index prunes the scan to one
   * session and the DISTINCT ON collapses fan-out IN THE DATABASE — the result is ~one row per bar
   * per side, not per strategy. Rows with no {@code confluence} block carry no dot verdicts at all
   * (early-rail blocks), so they are excluded here rather than fetched and discarded.
   */
  public List<BarSideDiagnostic> sessionBarSideDiagnostics(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        """
        SELECT DISTINCT ON (bar_time, side) bar_time, side, diagnostic
          FROM signal_rejections
         WHERE generated_at >= ? AND generated_at < ?
           AND diagnostic -> 'confluence' IS NOT NULL
         ORDER BY bar_time, side, id
        """,
        (rs, i) ->
            new BarSideDiagnostic(
                rs.getObject("bar_time", OffsetDateTime.class),
                rs.getString("side"),
                readTree(rs.getString("diagnostic"))),
        from, to);
  }

  /** Per-rail block counts over an optional window (the "which rail blocks most" rollup). */
  public List<RailCount> railCounts(UUID strategyVersionId, OffsetDateTime from, OffsetDateTime to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT blocking_rail, count(*) AS n FROM signal_rejections WHERE 1=1");
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
    sql.append(" GROUP BY blocking_rail ORDER BY n DESC");
    return jdbc.query(
        sql.toString(),
        (rs, i) -> new RailCount(rs.getString("blocking_rail"), rs.getLong("n")),
        args.toArray());
  }

  /**
   * One (rail, count) aggregate row.
   *
   * <p>A SECOND record with this simple name exists — {@code StrategyEvidenceReader.RailCount},
   * the dossier rejection profile. It publishes as {@code EvidenceRailCount} via {@code
   * @Schema(name)} (task_1c04803f), so the two no longer collapse; this one keeps the plain
   * {@code RailCount} name because the FE contracts bridge binds it. The ContractCaptureTest
   * collision assertion now fails any new same-named twin.
   */
  public record RailCount(String rail, long count) {}

  private RejectionRow row(ResultSet rs, int rowNum) throws SQLException {
    return new RejectionRow(
        rs.getLong("id"),
        rs.getObject("strategy_version_id", UUID.class),
        rs.getString("strategy_slug"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("interval"),
        rs.getString("side"),
        rs.getString("blocking_rail"),
        rs.getBigDecimal("blocking_operand"),
        rs.getBigDecimal("blocking_threshold"),
        rs.getBigDecimal("blocking_margin"),
        rs.getString("blocking_reason"),
        rs.getBigDecimal("composite_score"),
        rs.getBigDecimal("composite_threshold"),
        readTree(rs.getString("diagnostic")),
        rs.getObject("bar_time", OffsetDateTime.class),
        rs.getObject("generated_at", OffsetDateTime.class));
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored rejection diagnostic is not valid JSON", e);
    }
  }
}
