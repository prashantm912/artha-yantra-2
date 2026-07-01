package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code paper_positions}. The §F.6 partial-unique key guarantees at most one OPEN
 * position per {@code (exchange, tradingsymbol, side)}. Unrealized P&amp;L is NEVER persisted — only
 * realized P&amp;L (accumulated at close) lives on the row; unrealized is computed live elsewhere.
 */
@Repository
public class PaperPositionRepository {

  /** One position row. */
  public record PositionRow(
      long id,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal realizedPnl,
      String status,
      OffsetDateTime openedAt,
      OffsetDateTime closedAt,
      String closeReason,
      BigDecimal stopLoss,
      BigDecimal takeProfit) {}

  private static final String COLUMNS =
      "id, exchange, tradingsymbol, side, qty, avg_entry_price, realized_pnl, status,"
          + " opened_at, closed_at, close_reason, stop_loss, take_profit";

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperPositionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static PositionRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new PositionRow(
        rs.getLong("id"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("side"),
        rs.getLong("qty"),
        rs.getBigDecimal("avg_entry_price"),
        rs.getBigDecimal("realized_pnl"),
        rs.getString("status"),
        rs.getObject("opened_at", OffsetDateTime.class),
        rs.getObject("closed_at", OffsetDateTime.class),
        rs.getString("close_reason"),
        rs.getBigDecimal("stop_loss"),
        rs.getBigDecimal("take_profit"));
  }

  /** The OPEN position for a key+side, if any. */
  public Optional<PositionRow> findOpen(String exchange, String tradingsymbol, String side) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM paper_positions WHERE exchange=? AND tradingsymbol=?"
                + " AND side=? AND status='OPEN'",
            PaperPositionRepository::map,
            exchange,
            tradingsymbol,
            side)
        .stream()
        .findFirst();
  }

  /** A position by id. */
  public Optional<PositionRow> find(long id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM paper_positions WHERE id=?", PaperPositionRepository::map, id)
        .stream()
        .findFirst();
  }

  /** Opens a new position (optional SL/TP bracket levels), unstamped sub-account; returns the id. */
  public long insertOpen(
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit) {
    return insertOpen(exchange, tradingsymbol, side, qty, avgEntryPrice, stopLoss, takeProfit, null);
  }

  /** Opens a new position charged to a 5-account sub-ledger (E10); {@code subaccountIdx} null = unstamped. */
  public long insertOpen(
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      BigDecimal avgEntryPrice,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      Integer subaccountIdx) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO paper_positions
              (exchange, tradingsymbol, side, qty, avg_entry_price, status, opened_at, stop_loss, take_profit, subaccount_idx)
            VALUES (?,?,?,?,?, 'OPEN', now(), ?, ?, ?) RETURNING id
            """,
            Long.class,
            exchange,
            tradingsymbol,
            side,
            qty,
            avgEntryPrice,
            stopLoss,
            takeProfit,
            subaccountIdx);
    return id == null ? 0 : id;
  }

  /** Grows an open position (averaged entry). */
  public void updateOpen(long id, long qty, BigDecimal avgEntryPrice) {
    jdbc.update(
        "UPDATE paper_positions SET qty=?, avg_entry_price=? WHERE id=? AND status='OPEN'",
        qty,
        avgEntryPrice,
        id);
  }

  /** Closes a position with its realized P&amp;L + close reason (releases the partial-unique key). */
  public void close(long id, BigDecimal realizedPnl, String closeReason) {
    jdbc.update(
        "UPDATE paper_positions SET status='CLOSED', realized_pnl=?, closed_at=now(), close_reason=? WHERE id=?",
        realizedPnl,
        closeReason,
        id);
  }

  /** All OPEN positions. */
  public List<PositionRow> listOpen() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM paper_positions WHERE status='OPEN' ORDER BY opened_at DESC",
        PaperPositionRepository::map);
  }

  /** Closed trades in a window (optionally one tradingsymbol — the chart-marks fetch), newest first. */
  public List<PositionRow> listClosed(
      OffsetDateTime from, OffsetDateTime to, String tradingsymbol, int limit, int offset) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM paper_positions WHERE status='CLOSED'"
            + " AND (?::timestamptz IS NULL OR closed_at >= ?)"
            + " AND (?::timestamptz IS NULL OR closed_at <= ?)"
            + " AND (?::text IS NULL OR tradingsymbol = ?)"
            + " ORDER BY closed_at DESC LIMIT ? OFFSET ?",
        PaperPositionRepository::map,
        from,
        from,
        to,
        to,
        tradingsymbol,
        tradingsymbol,
        Math.min(Math.max(limit, 1), 500),
        Math.max(offset, 0));
  }

  /**
   * OPEN positions whose originating signal belongs to an {@code session.style: intraday} strategy —
   * the 15:45 mark-to-close set. All joins are SAME-schema (no cross-schema reference).
   */
  public List<PositionRow> intradayOpen() {
    return jdbc.query(
        """
        SELECT DISTINCT p.id, p.exchange, p.tradingsymbol, p.side, p.qty, p.avg_entry_price,
               p.realized_pnl, p.status, p.opened_at, p.closed_at, p.close_reason,
               p.stop_loss, p.take_profit
        FROM paper_positions p
        JOIN paper_orders o
          ON o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol AND o.side = p.side
          AND o.signal_id IS NOT NULL
        JOIN signals s ON s.id = o.signal_id
        JOIN strategy_versions sv ON sv.id = s.strategy_version_id
        WHERE p.status = 'OPEN' AND sv.config->'risk'->'session'->>'style' = 'intraday'
        """,
        PaperPositionRepository::map);
  }

  /** One open leg of a #11 straddle: the position id + its parent signal + that signal's detail. */
  public record StraddleLegRow(long positionId, long signalId, String scalperDetail) {}

  /**
   * Open positions whose parent signal is a #11 NEUTRAL straddle — the live combined-prem exit set. Both
   * legs of a straddle share the parent signal, so grouping the rows by {@code signalId} re-forms each
   * CE+PE pair; {@code scalper_detail} carries the underlying / expiry / ATM strike for the slLevel read.
   */
  public List<StraddleLegRow> openStraddleLegs() {
    return jdbc.query(
        """
        SELECT DISTINCT p.id, o.signal_id, s.scalper_detail::text AS scalper_detail
        FROM paper_positions p
        JOIN paper_orders o
          ON o.exchange = p.exchange AND o.tradingsymbol = p.tradingsymbol AND o.side = p.side
          AND o.signal_id IS NOT NULL
        JOIN signals s ON s.id = o.signal_id
        WHERE p.status = 'OPEN' AND s.scalper_detail->>'side' = 'NEUTRAL'
        """,
        (rs, n) ->
            new StraddleLegRow(
                rs.getLong("id"), rs.getLong("signal_id"), rs.getString("scalper_detail")));
  }

  /** The strategy that opened a position (via its first signal-linked order), for the T-1 audit. */
  public java.util.Optional<NotifyTarget> notifyTargetFor(
      String exchange, String tradingsymbol, String side) {
    return jdbc
        .query(
            """
            SELECT s2.id AS strategy_id, COALESCE(s2.notification_channel, 'NTFY') AS channel
            FROM paper_orders o
            JOIN signals sg ON sg.id = o.signal_id
            JOIN strategy_versions sv ON sv.id = sg.strategy_version_id
            JOIN strategies s2 ON s2.id = sv.strategy_id
            WHERE o.exchange=? AND o.tradingsymbol=? AND o.side=? AND o.signal_id IS NOT NULL
            ORDER BY o.id LIMIT 1
            """,
            (rs, n) ->
                new NotifyTarget(
                    java.util.UUID.fromString(rs.getString("strategy_id")), rs.getString("channel")),
            exchange,
            tradingsymbol,
            side)
        .stream()
        .findFirst();
  }

  /** A position's originating strategy + its notification channel. */
  public record NotifyTarget(java.util.UUID strategyId, String channel) {}

  /** Wipes the position ledger (paper reset). */
  public int deleteAll() {
    return jdbc.update("DELETE FROM paper_positions");
  }

  /** Count of OPEN positions (the {@code max_open_paper_positions} gate). */
  public int openCount() {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM paper_positions WHERE status='OPEN'", Integer.class);
    return count == null ? 0 : count;
  }

  /** Total realized P&amp;L over all closed trades. */
  public BigDecimal realizedTotal() {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_positions WHERE status='CLOSED'", BigDecimal.class);
  }

  /** Realized P&amp;L of trades closed on a given IST day (day-P&L input). */
  public BigDecimal realizedOn(java.time.LocalDate istDate) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(realized_pnl),0) FROM paper_positions"
            + " WHERE status='CLOSED' AND (closed_at AT TIME ZONE 'Asia/Kolkata')::date = ?",
        BigDecimal.class,
        java.sql.Date.valueOf(istDate));
  }

  /** Win/loss counts of trades closed on an IST day. */
  public record WinLoss(int wins, int losses) {}

  /**
   * Winning vs losing trades closed on a given IST day (the scalper 5-account discipline input —
   * §12.7). A trade with realized P&amp;L &gt; 0 is a win; ≤ 0 (flat or losing) is counted as a loss.
   */
  public WinLoss winLossOn(java.time.LocalDate istDate) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FILTER (WHERE realized_pnl > 0) AS wins,"
            + " COUNT(*) FILTER (WHERE realized_pnl <= 0) AS losses"
            + " FROM paper_positions WHERE status='CLOSED'"
            + " AND (closed_at AT TIME ZONE 'Asia/Kolkata')::date = ?",
        (rs, n) -> new WinLoss(rs.getInt("wins"), rs.getInt("losses")),
        java.sql.Date.valueOf(istDate));
  }

  /**
   * Per-sub-account win/loss tally for an IST day (the E10 five-account ledger). {@code realized} is
   * the day's net realized P&amp;L summed over the account's closed trades — the input to the
   * per-account 1%-profit-lock (r15-16).
   */
  public record SubAccountTally(int idx, int wins, int losses, BigDecimal realized) {
    /** Back-compat: pre-1%-bank callers/tests with no realized sum (defaults zero ⇒ never profit-locked). */
    public SubAccountTally(int idx, int wins, int losses) {
      this(idx, wins, losses, BigDecimal.ZERO);
    }
  }

  /**
   * Per-sub-account win/loss counts + net realized P&amp;L of trades closed on an IST day — the
   * 5-account ledger input (risk-governance.md §3.2). ONLY idx-carrying (scalper) closed trades are
   * tallied; a NULL {@code subaccount_idx} (non-scalper / legacy) row is invisible to this model, so
   * an empty result signals the day carries no ledger trades and {@link ScalperAccountModel} falls
   * back to the day-granularity {@link #winLossOn} count. Win/loss uses the same convention as
   * {@code winLossOn}: realized P&amp;L &gt; 0 is a win, ≤ 0 (flat or losing) a loss.
   */
  public java.util.List<SubAccountTally> subAccountTalliesOn(java.time.LocalDate istDate) {
    return jdbc.query(
        "SELECT subaccount_idx,"
            + " COUNT(*) FILTER (WHERE realized_pnl > 0) AS wins,"
            + " COUNT(*) FILTER (WHERE realized_pnl <= 0) AS losses,"
            + " COALESCE(SUM(realized_pnl), 0) AS realized"
            + " FROM paper_positions WHERE status='CLOSED' AND subaccount_idx IS NOT NULL"
            + " AND (closed_at AT TIME ZONE 'Asia/Kolkata')::date = ?"
            + " GROUP BY subaccount_idx",
        (rs, n) ->
            new SubAccountTally(
                rs.getInt("subaccount_idx"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                rs.getBigDecimal("realized")),
        java.sql.Date.valueOf(istDate));
  }

  /**
   * The per-sub-account capital fraction (config; equal 0.20 split by default) — the base for the
   * 1%-profit-lock target (account capital = equity × fraction). Read from {@code scalper_subaccount}.
   */
  public java.util.Map<Integer, BigDecimal> subAccountCapitalFractions() {
    return jdbc
        .query(
            "SELECT idx, capital_fraction FROM scalper_subaccount",
            (rs, n) -> java.util.Map.entry(rs.getInt("idx"), rs.getBigDecimal("capital_fraction")))
        .stream()
        .collect(
            java.util.stream.Collectors.toMap(
                java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
  }
}
