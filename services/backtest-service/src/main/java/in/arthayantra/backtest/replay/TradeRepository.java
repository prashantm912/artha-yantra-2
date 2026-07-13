package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists and reads {@code backtest_trades} — queryable trade rows (§D.3). */
@Repository
public class TradeRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC + Jackson. */
  public TradeRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Loads a run's trades (sequence order) as {@link Trade} records — the typed read behind the
   * MV-10.1 SwingReportCard grade. Populates the fields the report card reads (pnlPct, barsHeld) plus
   * the cheap scalars; {@code touchBasis}/{@code contributions} are left null (the grade never reads
   * them). Empty when the run has no trades.
   */
  public List<Trade> loadByRun(UUID runId) {
    return jdbc.query(
        "SELECT seq, side, qty, entry_ts, entry_price, exit_ts, exit_price, pnl, pnl_pct,"
            + " exit_reason, bars_held, exchange, tradingsymbol, stop_loss, take_profit"
            + " FROM backtest_trades WHERE run_id=? ORDER BY seq",
        (rs, n) ->
            new Trade(
                rs.getInt("seq"),
                in.arthayantra.strategyengine.fills.Side.valueOf(rs.getString("side")),
                rs.getLong("qty"),
                rs.getObject("entry_ts", OffsetDateTime.class),
                rs.getBigDecimal("entry_price"),
                rs.getObject("exit_ts", OffsetDateTime.class),
                rs.getBigDecimal("exit_price"),
                rs.getBigDecimal("pnl"),
                rs.getBigDecimal("pnl_pct"),
                rs.getString("exit_reason"),
                rs.getInt("bars_held"),
                null,
                null,
                rs.getString("exchange"),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("stop_loss"),
                rs.getBigDecimal("take_profit")),
        runId);
  }

  /** Batch-inserts a run's trades. */
  public void insertAll(UUID runId, List<Trade> trades) {
    jdbc.batchUpdate(
        """
        INSERT INTO backtest_trades (
          run_id, seq, side, qty, entry_ts, entry_price, exit_ts, exit_price,
          pnl, pnl_pct, exit_reason, bars_held, touch_basis, contributions,
          exchange, tradingsymbol, stop_loss, take_profit)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        trades,
        trades.size(),
        (ps, trade) -> {
          ps.setObject(1, runId);
          ps.setInt(2, trade.seq());
          ps.setString(3, trade.side().name());
          ps.setLong(4, trade.qty());
          ps.setObject(5, trade.entryTs());
          ps.setBigDecimal(6, trade.entryPrice());
          ps.setObject(7, trade.exitTs());
          ps.setBigDecimal(8, trade.exitPrice());
          ps.setBigDecimal(9, trade.pnl());
          ps.setBigDecimal(10, trade.pnlPct());
          // Canonical UPPERCASE casing at the single persistence chokepoint for every replay path
          // (candle / options-premium / deep-swing), so the shared table never mixes casing and lines
          // up with the UPPERCASE live-paper close_reason for the (still-dormant) reconciliation
          // metric (chip task_cf5d58c8).
          ps.setString(11, ExitReasons.canonical(trade.exitReason()));
          ps.setInt(12, trade.barsHeld());
          ps.setString(13, trade.touchBasis() == null ? null : trade.touchBasis().name());
          ps.setString(14, contributionsJson(trade));
          ps.setString(15, trade.exchange());
          ps.setString(16, trade.tradingsymbol());
          ps.setBigDecimal(17, trade.stopLoss());
          ps.setBigDecimal(18, trade.takeProfit());
        });
  }

  /**
   * Paged trades for one run, in sequence order. Optional {@code symbol} (exact tradingsymbol) and
   * {@code from}/{@code to} (entry-timestamp half-open window {@code [from, to)}) narrow the page;
   * any of them may be {@code null} to skip that predicate.
   */
  public List<Map<String, Object>> findByRun(
      UUID runId,
      int limit,
      int offset,
      String symbol,
      OffsetDateTime from,
      OffsetDateTime to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT seq, side, qty, entry_ts, entry_price, exit_ts, exit_price, pnl, pnl_pct, "
                + "exit_reason, bars_held, touch_basis, contributions, exchange, tradingsymbol, "
                + "stop_loss, take_profit FROM backtest_trades WHERE run_id=?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(runId);
    if (symbol != null && !symbol.isBlank()) {
      sql.append(" AND tradingsymbol=?");
      args.add(symbol);
    }
    if (from != null) {
      sql.append(" AND entry_ts >= ?");
      args.add(from);
    }
    if (to != null) {
      sql.append(" AND entry_ts < ?");
      args.add(to);
    }
    sql.append(" ORDER BY seq LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("seq", rs.getInt("seq"));
          row.put("side", rs.getString("side"));
          row.put("qty", rs.getLong("qty"));
          row.put("entryTs", ist(rs.getObject("entry_ts", OffsetDateTime.class)));
          row.put("entryPrice", rs.getBigDecimal("entry_price"));
          row.put("exitTs", ist(rs.getObject("exit_ts", OffsetDateTime.class)));
          row.put("exitPrice", rs.getBigDecimal("exit_price"));
          row.put("pnl", rs.getBigDecimal("pnl"));
          row.put("pnlPct", rs.getBigDecimal("pnl_pct"));
          row.put("exitReason", rs.getString("exit_reason"));
          row.put("barsHeld", rs.getInt("bars_held"));
          row.put("touchBasis", rs.getString("touch_basis"));
          row.put("contributions", parse(rs.getString("contributions")));
          row.put("exchange", rs.getString("exchange"));
          row.put("tradingsymbol", rs.getString("tradingsymbol"));
          row.put("stopLoss", rs.getBigDecimal("stop_loss"));
          row.put("takeProfit", rs.getBigDecimal("take_profit"));
          return row;
        },
        args.toArray());
  }

  /**
   * The closed-trade net P&amp;Ls for a run in trade order — the §D.16 Monte Carlo resampling pool.
   * Open-at-end trades (no {@code exit_ts}) are excluded; the bootstrap resamples realized P&amp;L.
   */
  public List<java.math.BigDecimal> findClosedPnls(UUID runId) {
    return jdbc.query(
        "SELECT pnl FROM backtest_trades WHERE run_id=? AND exit_ts IS NOT NULL ORDER BY seq",
        (rs, n) -> rs.getBigDecimal("pnl"),
        runId);
  }

  private String contributionsJson(Trade trade) {
    try {
      return trade.contributions() == null
          ? null
          : objectMapper.writeValueAsString(trade.contributions());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("trade contributions not serializable", e);
    }
  }

  private Object parse(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("corrupt trade contributions", e);
    }
  }

  private static String ist(OffsetDateTime timestamp) {
    return timestamp == null
        ? null
        : timestamp.withOffsetSameInstant(Ist.OFFSET).format(Ist.FORMATTER);
  }
}
