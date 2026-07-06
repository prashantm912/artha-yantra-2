package in.arthayantra.strategysignal.signals;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code strategy.shadow_positions} — the virtual 1-lot book of REJECTED scalper
 * entries (signal-analysis §7.1/§7.2). Deliberately separate from {@code paper_positions}: shadow
 * rows never touch risk limits, capital, the kill switch or the executed-trade analyses. INSERT
 * from the live rejection path, UPDATE (close) from {@link ShadowExitMonitor} only.
 */
@Repository
public class ShadowPositionRepository {

  /** One shadow position. */
  public record ShadowRow(
      long id,
      long rejectionId,
      UUID strategyVersionId,
      String strategySlug,
      String side,
      String underlying,
      String exchange,
      String tradingsymbol,
      BigDecimal strike,
      LocalDate expiry,
      BigDecimal entryLtp,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      BigDecimal structuralStop,
      String signalExchange,
      String signalTradingsymbol,
      String blockingRail,
      BigDecimal compositeScore,
      OffsetDateTime barTime,
      OffsetDateTime openedAt,
      String status,
      BigDecimal exitLtp,
      String closeReason,
      OffsetDateTime closedAt,
      BigDecimal pnlPoints,
      BigDecimal pnlPct,
      String variant,
      BigDecimal cost,
      BigDecimal pnlNet) {}

  /**
   * Per-variant book rollup (roadmap F1): counts + realized points over an optional window.
   * {@code pnlNet} (F8) is the 1-lot INR sum net of the statutory cost model — the honest number;
   * points stay for scale-free comparison. {@code unpriced} is the count of CLOSED rows whose
   * {@code pnl_net} is null (STALE closes, or pre-F8-cost-model history) — those rows are IN the
   * {@code closed} count but NOT in the {@code pnlNet} sum, so a consumer must surface it or the
   * book total reads as if the unpriced losers never happened.
   */
  public record VariantSummary(
      String variant, long open, long closed, long wins, long losses, BigDecimal pnlPoints,
      BigDecimal pnlNet, long unpriced) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public ShadowPositionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Opens one shadow position; returns its id. */
  public long insert(
      long rejectionId,
      UUID strategyVersionId,
      String strategySlug,
      String side,
      String underlying,
      String exchange,
      String tradingsymbol,
      BigDecimal strike,
      LocalDate expiry,
      BigDecimal entryLtp,
      BigDecimal stopLoss,
      BigDecimal takeProfit,
      BigDecimal structuralStop,
      String signalExchange,
      String signalTradingsymbol,
      String blockingRail,
      BigDecimal compositeScore,
      OffsetDateTime barTime,
      String variant) {
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO shadow_positions
              (rejection_id, strategy_version_id, strategy_slug, side, underlying, exchange,
               tradingsymbol, strike, expiry, entry_ltp, stop_loss, take_profit, structural_stop,
               signal_exchange, signal_tradingsymbol, blocking_rail, composite_score, bar_time,
               variant)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """,
            Long.class,
            rejectionId, strategyVersionId, strategySlug, side, underlying, exchange,
            tradingsymbol, strike, expiry, entryLtp, stopLoss, takeProfit, structuralStop,
            signalExchange, signalTradingsymbol, blockingRail, compositeScore, barTime, variant);
    return id == null ? -1 : id;
  }

  /** All OPEN shadow positions (oldest first — the monitor sweeps them in order). */
  public List<ShadowRow> open() {
    return jdbc.query(
        "SELECT * FROM shadow_positions WHERE status = 'OPEN' ORDER BY id", this::row);
  }

  /** True when an OPEN shadow already exists for this strategy+side+variant (the dedup rule). */
  public boolean hasOpen(String strategySlug, String side, String variant) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM shadow_positions "
                + "WHERE strategy_slug = ? AND side = ? AND variant = ? AND status = 'OPEN'",
            Long.class, strategySlug, side, variant);
    return n != null && n > 0;
  }

  /** OPEN positions in one variant book (each book has its own safety cap). */
  public long countOpen(String variant) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM shadow_positions WHERE variant = ? AND status = 'OPEN'",
            Long.class, variant);
    return n == null ? 0 : n;
  }

  /** Per-variant league rollup over an optional opened-at window (nulls = all history). */
  public List<VariantSummary> variantSummary(OffsetDateTime from, OffsetDateTime to) {
    return jdbc.query(
        """
        SELECT variant,
               count(*) FILTER (WHERE status = 'OPEN') AS open,
               count(*) FILTER (WHERE status = 'CLOSED') AS closed,
               count(*) FILTER (WHERE status = 'CLOSED' AND pnl_points > 0) AS wins,
               count(*) FILTER (WHERE status = 'CLOSED' AND pnl_points <= 0) AS losses,
               coalesce(sum(pnl_points) FILTER (WHERE status = 'CLOSED'), 0) AS pnl_points,
               sum(pnl_net) FILTER (WHERE status = 'CLOSED') AS pnl_net,
               count(*) FILTER (WHERE status = 'CLOSED' AND pnl_net IS NULL) AS unpriced
        FROM shadow_positions
        WHERE (?::timestamptz IS NULL OR opened_at >= ?) AND (?::timestamptz IS NULL OR opened_at < ?)
        GROUP BY variant ORDER BY variant
        """,
        (rs, i) ->
            new VariantSummary(
                rs.getString("variant"), rs.getLong("open"), rs.getLong("closed"),
                rs.getLong("wins"), rs.getLong("losses"), rs.getBigDecimal("pnl_points"),
                rs.getBigDecimal("pnl_net"), rs.getLong("unpriced")),
        from, from, to, to);
  }

  /**
   * Closes one shadow position with its outcome. {@code exitLtp}/pnl may be null (the STALE case —
   * the stack was down at square-off and no honest exit price exists); {@code cost}/{@code pnlNet}
   * (F8, 1-lot INR through the engine fill model) may be null when the lot size was unresolvable.
   */
  public void close(
      long id, String closeReason, BigDecimal exitLtp, BigDecimal pnlPoints, BigDecimal pnlPct,
      BigDecimal cost, BigDecimal pnlNet) {
    jdbc.update(
        """
        UPDATE shadow_positions
        SET status = 'CLOSED', close_reason = ?, exit_ltp = ?, pnl_points = ?, pnl_pct = ?,
            cost = ?, pnl_net = ?, closed_at = now()
        WHERE id = ? AND status = 'OPEN'
        """,
        closeReason, exitLtp, pnlPoints, pnlPct, cost, pnlNet, id);
  }

  private ShadowRow row(ResultSet rs, int rowNum) throws SQLException {
    java.sql.Date expiry = rs.getDate("expiry");
    return new ShadowRow(
        rs.getLong("id"),
        rs.getLong("rejection_id"),
        rs.getObject("strategy_version_id", UUID.class),
        rs.getString("strategy_slug"),
        rs.getString("side"),
        rs.getString("underlying"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getBigDecimal("strike"),
        expiry == null ? null : expiry.toLocalDate(),
        rs.getBigDecimal("entry_ltp"),
        rs.getBigDecimal("stop_loss"),
        rs.getBigDecimal("take_profit"),
        rs.getBigDecimal("structural_stop"),
        rs.getString("signal_exchange"),
        rs.getString("signal_tradingsymbol"),
        rs.getString("blocking_rail"),
        rs.getBigDecimal("composite_score"),
        rs.getObject("bar_time", OffsetDateTime.class),
        rs.getObject("opened_at", OffsetDateTime.class),
        rs.getString("status"),
        rs.getBigDecimal("exit_ltp"),
        rs.getString("close_reason"),
        rs.getObject("closed_at", OffsetDateTime.class),
        rs.getBigDecimal("pnl_points"),
        rs.getBigDecimal("pnl_pct"),
        rs.getString("variant"),
        rs.getBigDecimal("cost"),
        rs.getBigDecimal("pnl_net"));
  }
}
