package in.arthayantra.strategysignal.swing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable swing sell-decision rows (V037, audit §7.2.4 + §10 Phase 2). The daily HOLD/SELL triad is
 * still RECOMPUTED on read by {@link SwingSellDecisionService#report}; this repo persists a snapshot of
 * it at batch time so the owner has a history to acknowledge and the INT SELL_DECISION generator has a
 * durable substrate. One row per {@code (run_date, signal_id)}: a same-day catch-up re-run re-stamps the
 * verdict and clears the ack ONLY when the verdict changed (a fresh day is a new key).
 */
@Repository
public class SellDecisionRepository {

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public SellDecisionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Upserts one holding's decision for a run date (idempotent per {@code (run_date, signal_id)}). On a
   * same-day re-run the computed fields are re-stamped and {@code acknowledged_at} is cleared only if the
   * verdict actually changed — so an owner's ack survives a benign re-run but a flipped verdict re-surfaces.
   */
  public void upsert(
      String book, LocalDate runDate, long signalId, String exchange, String symbol, String setup,
      Integer stage, String setupType, String footprint, BigDecimal entryPrice, BigDecimal currentPrice,
      BigDecimal unrealizedPct, BigDecimal stopLevel, BigDecimal trailLevel, boolean stillBuyable,
      boolean sellingNow, String sellReason, String verdict) {
    jdbc.update(
        """
        INSERT INTO sell_decisions
            (book, run_date, evaluated_at, signal_id, exchange, symbol, setup, stage, setup_type,
             footprint, entry_price, current_price, unrealized_pct, stop_level, trail_level,
             still_buyable, selling_now, sell_reason, verdict)
        VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (run_date, signal_id) DO UPDATE SET
            evaluated_at = now(), book = EXCLUDED.book, exchange = EXCLUDED.exchange,
            symbol = EXCLUDED.symbol, setup = EXCLUDED.setup, stage = EXCLUDED.stage,
            setup_type = EXCLUDED.setup_type, footprint = EXCLUDED.footprint,
            entry_price = EXCLUDED.entry_price, current_price = EXCLUDED.current_price,
            unrealized_pct = EXCLUDED.unrealized_pct, stop_level = EXCLUDED.stop_level,
            trail_level = EXCLUDED.trail_level, still_buyable = EXCLUDED.still_buyable,
            selling_now = EXCLUDED.selling_now, sell_reason = EXCLUDED.sell_reason,
            verdict = EXCLUDED.verdict,
            acknowledged_at = CASE
                WHEN sell_decisions.verdict IS DISTINCT FROM EXCLUDED.verdict THEN NULL
                ELSE sell_decisions.acknowledged_at END
        """,
        book, java.sql.Date.valueOf(runDate), signalId, exchange, symbol, setup, stage, setupType,
        footprint, entryPrice, currentPrice, unrealizedPct, stopLevel, trailLevel, stillBuyable,
        sellingNow, sellReason, verdict);
  }

  /**
   * Recorded decisions newest first, optionally filtered by book and/or to the not-yet-acknowledged ones.
   * {@code limit}/{@code offset} page the history (the caller clamps them).
   */
  public List<SellDecisionRow> recent(String book, boolean unacknowledgedOnly, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM sell_decisions WHERE 1=1");
    List<Object> args = new java.util.ArrayList<>();
    if (book != null && !book.isBlank()) {
      sql.append(" AND book = ?");
      args.add(book);
    }
    if (unacknowledgedOnly) {
      sql.append(" AND acknowledged_at IS NULL");
    }
    sql.append(" ORDER BY run_date DESC, id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), SellDecisionRepository::map, args.toArray());
  }

  /**
   * Stamps {@code acknowledged_at = now()} for one row if it is not already acknowledged, then returns the
   * current row. Empty when the id does not exist; an already-acknowledged row is returned unchanged
   * (idempotent).
   */
  public Optional<SellDecisionRow> acknowledge(long id) {
    jdbc.update(
        "UPDATE sell_decisions SET acknowledged_at = now() WHERE id = ? AND acknowledged_at IS NULL", id);
    return jdbc.query("SELECT * FROM sell_decisions WHERE id = ?", SellDecisionRepository::map, id)
        .stream()
        .findFirst();
  }

  private static SellDecisionRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new SellDecisionRow(
        rs.getLong("id"),
        rs.getString("book"),
        rs.getObject("run_date", LocalDate.class),
        rs.getObject("evaluated_at", OffsetDateTime.class),
        rs.getLong("signal_id"),
        rs.getString("exchange"),
        rs.getString("symbol"),
        rs.getString("setup"),
        rs.getObject("stage", Integer.class),
        rs.getString("setup_type"),
        rs.getString("footprint"),
        rs.getBigDecimal("entry_price"),
        rs.getBigDecimal("current_price"),
        rs.getBigDecimal("unrealized_pct"),
        rs.getBigDecimal("stop_level"),
        rs.getBigDecimal("trail_level"),
        rs.getBoolean("still_buyable"),
        rs.getBoolean("selling_now"),
        rs.getString("sell_reason"),
        rs.getString("verdict"),
        rs.getObject("acknowledged_at", OffsetDateTime.class));
  }

  /** One persisted decision (the read + ack shape). {@code acknowledgedAt} null = not yet acknowledged. */
  public record SellDecisionRow(
      long id,
      String book,
      LocalDate runDate,
      OffsetDateTime evaluatedAt,
      long signalId,
      String exchange,
      String symbol,
      String setup,
      Integer stage,
      String setupType,
      String footprint,
      BigDecimal entryPrice,
      BigDecimal currentPrice,
      BigDecimal unrealizedPct,
      BigDecimal stopLevel,
      BigDecimal trailLevel,
      boolean stillBuyable,
      boolean sellingNow,
      String sellReason,
      String verdict,
      OffsetDateTime acknowledgedAt) {}

  /** The {items} envelope for the recorded-decisions read endpoint. */
  public record SellDecisions(List<SellDecisionRow> items) {}
}
