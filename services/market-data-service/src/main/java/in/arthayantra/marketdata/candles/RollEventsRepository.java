package in.arthayantra.marketdata.candles;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC access to {@code roll_events} (B-19) — append-only, idempotent on the natural PK. */
@Repository
public class RollEventsRepository {

  /** One roll. */
  public record RollEvent(
      String underlying,
      LocalDate rollDate,
      String fromTradingsymbol,
      String toTradingsymbol,
      BigDecimal priceGap) {}

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public RollEventsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Appends one roll; replays are no-ops (PK). Returns true when the row was new. */
  public boolean append(RollEvent event) {
    return jdbc.update(
            """
            INSERT INTO roll_events (underlying, roll_date, from_tradingsymbol, to_tradingsymbol, price_gap)
            VALUES (?,?,?,?,?) ON CONFLICT (underlying, roll_date) DO NOTHING
            """,
            event.underlying(),
            Date.valueOf(event.rollDate()),
            event.fromTradingsymbol(),
            event.toTradingsymbol(),
            event.priceGap())
        > 0;
  }

  /** All rolls for an underlying, date-ordered. */
  public List<RollEvent> rollsFor(String underlying) {
    return jdbc.query(
        "SELECT * FROM roll_events WHERE underlying = ? ORDER BY roll_date",
        (rs, n) ->
            new RollEvent(
                rs.getString("underlying"),
                rs.getDate("roll_date").toLocalDate(),
                rs.getString("from_tradingsymbol"),
                rs.getString("to_tradingsymbol"),
                rs.getBigDecimal("price_gap")),
        underlying);
  }
}
