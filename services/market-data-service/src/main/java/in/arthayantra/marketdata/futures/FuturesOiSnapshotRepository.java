package in.arthayantra.marketdata.futures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC writer for the futures OI time-series (mirrors OptionsSnapshotRepository). */
@Repository
public class FuturesOiSnapshotRepository {

  /** One contract's snapshot at {@code ts}. */
  public record Row(
      OffsetDateTime ts,
      String underlying,
      String tradingsymbol,
      LocalDate expiry,
      BigDecimal ltp,
      Long volume,
      Long oi,
      Long oiChange,
      BigDecimal dayOpen,
      BigDecimal dayHigh,
      BigDecimal dayLow,
      BigDecimal prevClose) {}

  private final JdbcTemplate jdbc;

  public FuturesOiSnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Batch-inserts a snapshot pass; a duplicate (ts, underlying, tradingsymbol) is ignored. */
  public void insertAll(List<Row> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO futures_oi_snapshots
          (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change,
           day_open, day_high, day_low, prev_close)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (ts, underlying, tradingsymbol) DO NOTHING
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.ts());
          ps.setString(2, r.underlying());
          ps.setString(3, r.tradingsymbol());
          ps.setObject(4, r.expiry());
          ps.setBigDecimal(5, r.ltp());
          ps.setObject(6, r.volume());
          ps.setObject(7, r.oi());
          ps.setObject(8, r.oiChange());
          ps.setBigDecimal(9, r.dayOpen());
          ps.setBigDecimal(10, r.dayHigh());
          ps.setBigDecimal(11, r.dayLow());
          ps.setBigDecimal(12, r.prevClose());
        });
  }
}
