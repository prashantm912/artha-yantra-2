package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  /** Batch-inserts a run's trades. */
  public void insertAll(UUID runId, List<Trade> trades) {
    jdbc.batchUpdate(
        """
        INSERT INTO backtest_trades (
          run_id, seq, side, qty, entry_ts, entry_price, exit_ts, exit_price,
          pnl, pnl_pct, exit_reason, bars_held, touch_basis, contributions)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
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
          ps.setString(11, trade.exitReason());
          ps.setInt(12, trade.barsHeld());
          ps.setString(13, trade.touchBasis() == null ? null : trade.touchBasis().name());
          ps.setString(14, contributionsJson(trade));
        });
  }

  /** Paged trades for one run, in sequence order. */
  public List<Map<String, Object>> findByRun(UUID runId, int limit, int offset) {
    return jdbc.query(
        "SELECT seq, side, qty, entry_ts, entry_price, exit_ts, exit_price, pnl, pnl_pct, "
            + "exit_reason, bars_held, touch_basis, contributions FROM backtest_trades "
            + "WHERE run_id=? ORDER BY seq LIMIT ? OFFSET ?",
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("seq", rs.getInt("seq"));
          row.put("side", rs.getString("side"));
          row.put("qty", rs.getLong("qty"));
          row.put("entryTs", rs.getString("entry_ts"));
          row.put("entryPrice", rs.getBigDecimal("entry_price"));
          row.put("exitTs", rs.getString("exit_ts"));
          row.put("exitPrice", rs.getBigDecimal("exit_price"));
          row.put("pnl", rs.getBigDecimal("pnl"));
          row.put("pnlPct", rs.getBigDecimal("pnl_pct"));
          row.put("exitReason", rs.getString("exit_reason"));
          row.put("barsHeld", rs.getInt("bars_held"));
          row.put("touchBasis", rs.getString("touch_basis"));
          row.put("contributions", parse(rs.getString("contributions")));
          return row;
        },
        runId,
        limit,
        offset);
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
}
