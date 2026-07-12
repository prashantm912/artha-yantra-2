package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code paper_events} (V036) — the append-only paper-position lifecycle ledger (audit
 * §7.2.2). The writer only ever INSERTs; the reader serves the position-detail / trade-chain and the
 * live feed. IST day filtering bounds {@code created_at} by explicit +05:30 instants (in-container
 * {@code now()} is UTC — never {@code ::date}).
 */
@Repository
public class PaperEventRepository {

  /** IST — the day filter's calendar; {@code created_at} is stored UTC (timestamptz). */
  private static final ZoneOffset IST = ZoneOffset.of("+05:30");

  /** One lifecycle-event row. */
  public record PaperEventRow(
      long id,
      long positionId,
      String kind,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String reason,
      BigDecimal realizedPnl,
      OffsetDateTime createdAt) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperEventRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Appends one event; returns its id (stamped into the {@code paper.events} frame). */
  public long insert(
      long positionId,
      String kind,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String reason,
      BigDecimal realizedPnl) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO paper_events"
                + " (position_id, kind, book, exchange, tradingsymbol, side, qty, reason, realized_pnl)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            positionId, kind, book, exchange, tradingsymbol, side, qty, reason, realizedPnl);
    return id == null ? -1 : id;
  }

  private static PaperEventRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new PaperEventRow(
        rs.getLong("id"),
        rs.getLong("position_id"),
        rs.getString("kind"),
        rs.getString("book"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("side"),
        rs.getLong("qty"),
        rs.getString("reason"),
        rs.getBigDecimal("realized_pnl"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  /**
   * Newest-first events with optional filters ({@code positionId} | {@code book} | IST {@code day}) —
   * the read behind {@code GET /api/v1/paper/events}. All filters are ANDed; any may be null.
   */
  public List<PaperEventRow> query(
      Long positionId, String book, LocalDate day, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM paper_events WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (positionId != null) {
      sql.append(" AND position_id = ?");
      args.add(positionId);
    }
    if (book != null && !book.isBlank()) {
      sql.append(" AND book = ?");
      args.add(book);
    }
    if (day != null) {
      OffsetDateTime start = day.atStartOfDay().atOffset(IST);
      sql.append(" AND created_at >= ? AND created_at < ?");
      args.add(start);
      args.add(start.plusDays(1));
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), PaperEventRepository::map, args.toArray());
  }
}
