package in.arthayantra.marketdata.screener.minervini.geometry;

import in.arthayantra.marketdata.screener.AdjustedEquityDailySql;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads a single equity's recent daily OHLCV series from {@code nse_eod_bhavcopy} (the same broad
 * EQ/BE universe the {@code TrendTemplateService} screen runs over — NOT the sparse candle store),
 * through the CA-adjusted shared price plane ({@link AdjustedEquityDailySql}, audit H6 / FID P0-4) so
 * a split/bonus inside the base window no longer opens a false price cliff under the VCP geometry.
 * Feeds {@link VcpDetector}. Ordered oldest→newest; windowed to a calendar-day lookback so a
 * ~40-week base plus the 50-session volume tail is always covered.
 */
@Repository
public class DailyBarReader {

  private static final String SQL = AdjustedEquityDailySql.GEOMETRY_SYMBOL_SQL;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public DailyBarReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The symbol's daily bars from {@code (asOf - lookbackDays)} through {@code asOf}, oldest first. */
  public List<DailyBar> read(String symbol, LocalDate asOf, int lookbackDays) {
    Date d = Date.valueOf(asOf);
    return jdbc.query(
        SQL,
        (rs, n) ->
            new DailyBar(
                rs.getObject("trade_date", LocalDate.class),
                dbl(rs.getBigDecimal("open_price")),
                dbl(rs.getBigDecimal("high_price")),
                dbl(rs.getBigDecimal("low_price")),
                dbl(rs.getBigDecimal("close_price")),
                rs.getLong("ttl_trd_qnty")),
        symbol, d, d, lookbackDays);
  }

  private static double dbl(java.math.BigDecimal v) {
    return v == null ? 0.0 : v.doubleValue();
  }
}
