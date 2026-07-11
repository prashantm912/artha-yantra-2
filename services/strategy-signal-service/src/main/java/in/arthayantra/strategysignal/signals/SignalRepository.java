package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to the signals table (C-2.12). */
@Repository
public class SignalRepository {

  /** One signal row. */
  public record SignalRow(
      long id,
      UUID strategyVersionId,
      String exchange,
      String tradingsymbol,
      String interval,
      String signalType,
      String side,
      BigDecimal entryPrice,
      BigDecimal stopLoss,
      BigDecimal target,
      BigDecimal compositeScore,
      JsonNode scoreBreakdown,
      String status,
      OffsetDateTime generatedAt,
      OffsetDateTime expiresAt,
      BigDecimal suggestedQty,
      String tradeableExchange,
      String tradeableTradingsymbol,
      JsonNode scalperDetail,
      JsonNode minerviniDetail,
      JsonNode manasAroraDetail) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires the strategy datasource. */
  public SignalRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Persists one signal; returns its id. */
  public long insert(
      UUID strategyVersionId,
      String exchange,
      String tradingsymbol,
      String interval,
      String signalType,
      String side,
      BigDecimal entryPrice,
      BigDecimal stopLoss,
      BigDecimal target,
      BigDecimal compositeScore,
      String scoreBreakdownJson,
      OffsetDateTime generatedAt,
      OffsetDateTime expiresAt) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               entry_price, stop_loss, target, composite_score, score_breakdown,
               generated_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING id
            """,
            Long.class,
            strategyVersionId, exchange, tradingsymbol, interval, signalType, side,
            entryPrice, stopLoss, target, compositeScore, scoreBreakdownJson,
            generatedAt, expiresAt);
    return id == null ? -1 : id;
  }

  /** Paged history with optional filters. */
  public List<SignalRow> list(
      String status, UUID strategyVersionId, String exchange, String tradingsymbol,
      OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
    return list(status, null, strategyVersionId, exchange, tradingsymbol, from, to, limit, offset);
  }

  /**
   * Paged history with optional filters, including a paper BOOK (strategy family) filter — a family
   * tag ({@code scalper}/{@code minervini}/{@code manas-arora}) matched against the originating
   * strategy's {@code strategies.tags} so each book's signals page shows only its own signals.
   */
  public List<SignalRow> list(
      String status, String book, UUID strategyVersionId, String exchange, String tradingsymbol,
      OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT s.* FROM signals s WHERE 1=1");
    List<Object> args = new java.util.ArrayList<>();
    if (status != null) {
      sql.append(" AND s.status = ?");
      args.add(status);
    }
    if (book != null && !book.isBlank()) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM strategy_versions sv JOIN strategies st ON st.id = sv.strategy_id"
              + " WHERE sv.id = s.strategy_version_id AND ? = ANY(st.tags))");
      args.add(book);
    }
    if (strategyVersionId != null) {
      sql.append(" AND s.strategy_version_id = ?");
      args.add(strategyVersionId);
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

  /** All ACTIVE signals, newest first. */
  public List<SignalRow> active() {
    return jdbc.query(
        "SELECT * FROM signals WHERE status = 'ACTIVE' ORDER BY generated_at DESC, id DESC",
        this::row);
  }

  /**
   * Every live ENTRY anchor across all strategies (ACTIVE or TAKEN) — the Phase-9 Minervini swing
   * batch's exit-pass driver: each active swing entry is a held-position anchor whose exit rules the
   * daily batch re-evaluates on the fresh daily bar (the tick engine can't, since equities don't tick).
   */
  public List<SignalRow> activeEntries() {
    return jdbc.query(
        "SELECT * FROM signals WHERE signal_type = 'ENTRY' AND status IN ('ACTIVE', 'TAKEN')"
            + " ORDER BY generated_at DESC, id DESC",
        this::row);
  }

  /** One signal. */
  public Optional<SignalRow> find(long id) {
    return jdbc.query("SELECT * FROM signals WHERE id = ?", this::row, id).stream().findFirst();
  }

  /**
   * The newest live ENTRY for a (version, instrument) — the exit evaluator's anchor. TAKEN entries
   * stay anchored: a take opens a paper position, so the engine's exit passes (structural stop /
   * confluence flip / ExitEvaluator / intrabar levels) and re-entry suppression must keep running
   * until the position resolves — anchoring only ACTIVE exit-orphaned every taken position.
   */
  public Optional<SignalRow> activeEntry(UUID versionId, String exchange, String tradingsymbol) {
    return jdbc.query(
            """
            SELECT * FROM signals
            WHERE strategy_version_id = ? AND exchange = ? AND tradingsymbol = ?
              AND signal_type = 'ENTRY' AND status IN ('ACTIVE', 'TAKEN')
            ORDER BY generated_at DESC, id DESC LIMIT 1
            """,
            this::row,
            versionId, exchange, tradingsymbol)
        .stream()
        .findFirst();
  }

  /** Lifecycle transition; returns false when the row is missing. */
  public boolean transition(long id, String status) {
    return jdbc.update("UPDATE signals SET status = ? WHERE id = ?", status, id) > 0;
  }

  /** Guarded lifecycle transition — updates only when the row is still in {@code from}. */
  public boolean transitionIf(long id, String from, String to) {
    return jdbc.update(
            "UPDATE signals SET status = ? WHERE id = ? AND status = ?", to, id, from)
        > 0;
  }

  /**
   * The canonical config JSON of a version — the paper layer reads the YAML's {@code exit_rules}
   * premium_pct percentages at take time to derive option-premium bracket levels (P1-8). Same-schema
   * read; empty when the version is gone.
   */
  public Optional<com.fasterxml.jackson.databind.JsonNode> versionConfig(UUID versionId) {
    return jdbc
        .query(
            "SELECT config FROM strategy_versions WHERE id = ?",
            (rs, n) -> {
              try {
                return objectMapper.readTree(rs.getString("config"));
              } catch (Exception e) {
                return null;
              }
            },
            versionId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  /**
   * The 15:45 INTRADAY sweep: expires stale ACTIVE rows — but NOT swing (session.style=swing) anchors,
   * which the daily Minervini/Manas batches hold across sessions (an ACTIVE swing ENTRY that has not
   * yet been auto-taken must survive to be evaluated / manually taken the next evening, mirroring the
   * style filter the paper 15:45 mark-to-close already uses). Swing versions are excluded by id; every
   * other (intraday / null-style) ACTIVE row expires as before.
   */
  public int expireAllActive() {
    return jdbc.update(
        "UPDATE signals SET status = 'EXPIRED' WHERE status = 'ACTIVE'"
            + " AND strategy_version_id NOT IN ("
            + "   SELECT sv.id FROM strategy_versions sv"
            + "   WHERE sv.config->'risk'->'session'->>'style' = 'swing')");
  }

  private SignalRow row(ResultSet rs, int rowNum) throws SQLException {
    return new SignalRow(
        rs.getLong("id"),
        rs.getObject("strategy_version_id", UUID.class),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("interval"),
        rs.getString("signal_type"),
        rs.getString("side"),
        rs.getBigDecimal("entry_price"),
        rs.getBigDecimal("stop_loss"),
        rs.getBigDecimal("target"),
        rs.getBigDecimal("composite_score"),
        readTree(rs.getString("score_breakdown")),
        rs.getString("status"),
        rs.getObject("generated_at", OffsetDateTime.class),
        rs.getObject("expires_at", OffsetDateTime.class),
        rs.getBigDecimal("suggested_qty"),
        rs.getString("tradeable_exchange"),
        rs.getString("tradeable_tradingsymbol"),
        nullableTree(rs.getString("scalper_detail")),
        nullableTree(rs.getString("minervini_detail")),
        nullableTree(rs.getString("manas_arora_detail")));
  }

  /** Stamps the engine-computed suggested qty (A12) — outside the frozen score breakdown. */
  public void stampSuggestedQty(long id, BigDecimal qty) {
    jdbc.update("UPDATE signals SET suggested_qty = ? WHERE id = ?", qty, id);
  }

  /**
   * Stamps the §12.9 scalper side-channel: the option the seam picked to trade (the signal is keyed
   * on the index future) plus the confluence detail JSON — outside the frozen score breakdown.
   */
  public void stampScalperDetail(
      long id, String tradeableExchange, String tradeableTradingsymbol, String detailJson) {
    jdbc.update(
        "UPDATE signals SET tradeable_exchange = ?, tradeable_tradingsymbol = ?, "
            + "scalper_detail = ?::jsonb WHERE id = ?",
        tradeableExchange, tradeableTradingsymbol, detailJson, id);
  }

  /**
   * Stamps the INT §13 row 19 / FID P1-8 fired-side rail-operand side-channel (V035): the full condition
   * matrix a scalper ENTRY cleared (every rail's operand + threshold + margin, the Connect-the-Dots
   * confluence, the raw OI/macro/chart context), mirroring {@code signal_rejections.diagnostic} so the
   * §4.2 Stage-2 fired-vs-rejected contrast joins cleanly — outside the frozen score breakdown, the same
   * rule as {@link #stampScalperDetail}. Best-effort (the caller swallows failures — a diagnostic must
   * never break the live signal path); NULL for every non-scalper signal (never stamped).
   */
  public void stampFiredDiagnostic(long id, String diagnosticJson) {
    jdbc.update("UPDATE signals SET fired_diagnostic = ?::jsonb WHERE id = ?", diagnosticJson, id);
  }

  /**
   * Stamps the MV-6.8 minervini side-channel: the fired swing setup's detail (setup type, stage, VCP
   * footprint, pivot, gate booleans) as JSON — outside the frozen score breakdown, the same rule as
   * {@link #stampScalperDetail}. NULL for every non-minervini signal (never stamped).
   */
  public void stampMinerviniDetail(long id, String detailJson) {
    jdbc.update("UPDATE signals SET minervini_detail = ?::jsonb WHERE id = ?", detailJson, id);
  }

  /**
   * Stamps the Manas Arora swing side-channel (V022): the fired setup's detail (setup type, base
   * footprint, pivot) as JSON — outside the frozen score breakdown, the same rule as {@link
   * #stampMinerviniDetail}. NULL for every non-manas signal (never stamped).
   */
  public void stampManasAroraDetail(long id, String detailJson) {
    jdbc.update("UPDATE signals SET manas_arora_detail = ?::jsonb WHERE id = ?", detailJson, id);
  }

  private JsonNode readTree(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored breakdown is not valid JSON", e);
    }
  }

  /** Same as {@link #readTree} but tolerant of a NULL column (the scalper side-channel). */
  private JsonNode nullableTree(String json) {
    return json == null ? null : readTree(json);
  }
}
