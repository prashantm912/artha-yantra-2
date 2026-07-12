package in.arthayantra.strategysignal.paper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code paper_order_rejections} (V039) — the append-only ledger of REFUSED paper-order
 * attempts (audit P1-4). openOrder throws {@code DATA_STALE} (stale last tick / no price) BEFORE any
 * {@code paper_orders} row is written, so this is the only durable trace of an order that never filled.
 * The writer only ever INSERTs; the reader serves a rejects feed. IST day filtering bounds
 * {@code created_at} by explicit +05:30 instants (in-container {@code now()} is UTC — never {@code ::date}).
 */
@Repository
public class PaperOrderRejectionRepository {

  /** IST — the day filter's calendar; {@code created_at} is stored UTC (timestamptz). */
  private static final ZoneOffset IST = ZoneOffset.of("+05:30");

  /** One rejection row. */
  public record RejectionRow(
      long id,
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String reason,
      String detail,
      Long tickAgeMs,
      OffsetDateTime createdAt) {}

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public PaperOrderRejectionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Appends one rejection row. */
  public void insert(
      Long signalId,
      String book,
      String exchange,
      String tradingsymbol,
      String side,
      long qty,
      String reason,
      String detail,
      Long tickAgeMs) {
    jdbc.update(
        "INSERT INTO paper_order_rejections"
            + " (signal_id, book, exchange, tradingsymbol, side, qty, reason, detail, tick_age_ms)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        signalId, book, exchange, tradingsymbol, side, qty, reason, detail, tickAgeMs);
  }

  /** Newest-first rejections with optional {@code book} / IST {@code day} filters (a rejects feed). */
  public List<RejectionRow> query(String book, LocalDate day, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM paper_order_rejections WHERE 1=1");
    List<Object> args = new ArrayList<>();
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
    return jdbc.query(sql.toString(), PaperOrderRejectionRepository::map, args.toArray());
  }

  private static RejectionRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new RejectionRow(
        rs.getLong("id"),
        rs.getObject("signal_id", Long.class),
        rs.getString("book"),
        rs.getString("exchange"),
        rs.getString("tradingsymbol"),
        rs.getString("side"),
        rs.getLong("qty"),
        rs.getString("reason"),
        rs.getString("detail"),
        rs.getObject("tick_age_ms", Long.class),
        rs.getObject("created_at", OffsetDateTime.class));
  }
}
