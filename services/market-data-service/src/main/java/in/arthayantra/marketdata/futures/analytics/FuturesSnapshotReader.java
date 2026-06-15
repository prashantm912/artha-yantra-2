package in.arthayantra.marketdata.futures.analytics;

import in.arthayantra.marketdata.options.OiInterval;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Query-time downsample of futures_oi_snapshots, per contract. */
@Repository
public class FuturesSnapshotReader {

  private final JdbcTemplate jdbc;

  public FuturesSnapshotReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record FutPoint(
      OffsetDateTime bucket, String tradingsymbol, BigDecimal ltp, Long oi, Long oiChange) {}

  public List<FutPoint> series(
      String underlying, OiInterval interval, OffsetDateTime from, OffsetDateTime to) {
    String sql =
        "SELECT public.time_bucket(INTERVAL '"
            + interval.pgInterval()
            + "', ts) AS b, "
            + "  tradingsymbol, public.last(ltp, ts) AS ltp, public.last(oi, ts) AS oi, "
            + "  public.last(oi_change, ts) AS oi_change "
            + "FROM futures_oi_snapshots "
            + "WHERE underlying = ? AND ts >= ? AND ts < ? "
            + "GROUP BY b, tradingsymbol ORDER BY b, tradingsymbol";
    return jdbc.query(
        sql,
        (rs, n) ->
            new FutPoint(
                rs.getObject("b", OffsetDateTime.class),
                rs.getString("tradingsymbol"),
                rs.getBigDecimal("ltp"),
                rs.getObject("oi", Long.class),
                rs.getObject("oi_change", Long.class)),
        underlying,
        Timestamp.from(from.toInstant()),
        Timestamp.from(to.toInstant()));
  }

  /** Most recent snapshot bucket per contract (clock-independent: anchors on max(ts)). */
  public List<FutPoint> latest(String underlying, OiInterval interval) {
    List<OffsetDateTime> max =
        jdbc.query(
            "SELECT max(ts) AS m FROM futures_oi_snapshots WHERE underlying = ?",
            (rs, n) -> rs.getObject("m", OffsetDateTime.class),
            underlying);
    OffsetDateTime maxTs = max.isEmpty() ? null : max.get(0);
    if (maxTs == null) {
      return List.of();
    }
    return series(underlying, interval, maxTs.minus(interval.bucket()), maxTs.plusSeconds(1));
  }
}
