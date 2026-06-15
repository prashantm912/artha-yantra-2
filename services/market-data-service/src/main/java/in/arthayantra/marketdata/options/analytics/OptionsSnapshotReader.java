package in.arthayantra.marketdata.options.analytics;

import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Query-time downsample of option_chain_snapshots (NO cagg — decision 2026-06-15). */
@Repository
public class OptionsSnapshotReader {

  private final JdbcTemplate jdbc;

  public OptionsSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One downsampled point per (bucket, strike, optionType): last() of each point-in-time stat. */
  public record StrikePoint(
      OffsetDateTime bucket,
      BigDecimal strike,
      String optionType,
      BigDecimal ltp,
      Long oi,
      Long oiChange,
      BigDecimal iv,
      BigDecimal spot) {}

  public List<StrikePoint> series(
      String underlying,
      LocalDate expiry,
      OiInterval interval,
      OffsetDateTime from,
      OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts) AS b, "
            + "  strike, option_type, "
            + "  public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change, public.last(iv, ts) AS iv, "
            + "  public.last(spot_price, ts) AS spot "
            + "FROM options_chain_snapshots "
            + "WHERE underlying = ? AND expiry = ? AND ts >= ? AND ts < ? "
            + "GROUP BY b, strike, option_type "
            + "ORDER BY b, strike, option_type";
    return jdbc.query(
        sql,
        (rs, n) ->
            new StrikePoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getBigDecimal("strike"),
                rs.getString("option_type"),
                rs.getBigDecimal("ltp"),
                rs.getObject("oi", Long.class),
                rs.getObject("oi_change", Long.class),
                rs.getBigDecimal("iv"),
                rs.getBigDecimal("spot")),
        underlying,
        java.sql.Date.valueOf(expiry),
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  /** The most recent snapshot bucket's rows (for "current" analytics). Empty if none. */
  public List<StrikePoint> latest(String underlying, LocalDate expiry, OiInterval interval) {
    List<OffsetDateTime> max =
        jdbc.query(
            "SELECT max(ts) AS m FROM options_chain_snapshots WHERE underlying = ? AND expiry = ?",
            (rs, n) -> rs.getObject("m", OffsetDateTime.class),
            underlying,
            java.sql.Date.valueOf(expiry));
    OffsetDateTime maxTs = max.isEmpty() ? null : max.get(0);
    if (maxTs == null) {
      return List.of();
    }
    // one bucket wide, ending just after maxTs
    return series(
        underlying, expiry, interval, maxTs.minus(interval.bucket()), maxTs.plusSeconds(1));
  }
}
