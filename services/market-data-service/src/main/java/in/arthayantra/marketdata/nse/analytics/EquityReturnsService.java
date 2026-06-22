package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.constituents.StockSectorMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * oipulse "Equity Returns": a multi-timeframe returns screener over every EQ stock — the latest close
 * (LTP) and the % return over 1 day / 1 week / 1 month / 6 months / 1 year, each measured against the
 * close that many TRADING sessions back (rn 2/6/22/127/253 by recency). Sector comes from the static
 * {@link StockSectorMap}. NSE bhavcopy history forward-accrues (~quarter deep early on), so the longer
 * windows stay {@code null} until enough sessions accrue — the structure is faithful from day one.
 */
@Service
public class EquityReturnsService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final JdbcTemplate jdbc;
  private final StockSectorMap sectors;

  public EquityReturnsService(JdbcTemplate jdbc, StockSectorMap sectors) {
    this.jdbc = jdbc;
    this.sectors = sectors;
  }

  /** One screener row: LTP + the five window returns (% strings; {@code null} when no base close). */
  public record ReturnsRow(
      String symbol,
      String industry,
      BigDecimal ltp,
      BigDecimal r1d,
      BigDecimal r1w,
      BigDecimal r1m,
      BigDecimal r6m,
      BigDecimal r1y) {}

  /** The whole screener for the latest accrued session. */
  public record Returns(LocalDate asOf, List<ReturnsRow> items) {}

  public Returns returns() {
    // One pass: rank each symbol's EQ closes by recency, then pluck the base closes for each window.
    List<Base> bases =
        jdbc.query(
            "WITH ranked AS ("
                + "  SELECT symbol, close_price, "
                + "    ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn "
                + "  FROM nse_eod_bhavcopy WHERE series = 'EQ') "
                + "SELECT symbol, "
                + "  max(close_price) FILTER (WHERE rn = 1)   AS c0, "
                + "  max(close_price) FILTER (WHERE rn = 2)   AS c1d, "
                + "  max(close_price) FILTER (WHERE rn = 6)   AS c1w, "
                + "  max(close_price) FILTER (WHERE rn = 22)  AS c1m, "
                + "  max(close_price) FILTER (WHERE rn = 127) AS c6m, "
                + "  max(close_price) FILTER (WHERE rn = 253) AS c1y "
                + "FROM ranked WHERE rn IN (1, 2, 6, 22, 127, 253) "
                + "GROUP BY symbol",
            (rs, n) ->
                new Base(
                    rs.getString("symbol"),
                    rs.getBigDecimal("c0"),
                    rs.getBigDecimal("c1d"),
                    rs.getBigDecimal("c1w"),
                    rs.getBigDecimal("c1m"),
                    rs.getBigDecimal("c6m"),
                    rs.getBigDecimal("c1y")));
    List<ReturnsRow> rows = new ArrayList<>();
    for (Base b : bases) {
      if (b.c0 == null) {
        continue; // no latest close — nothing to report
      }
      String industry = sectors.sector(b.symbol);
      if (industry == null) {
        continue; // restrict to the known (sector-mapped) universe — the curated screener set
      }
      rows.add(
          new ReturnsRow(
              b.symbol,
              industry,
              b.c0,
              ret(b.c0, b.c1d),
              ret(b.c0, b.c1w),
              ret(b.c0, b.c1m),
              ret(b.c0, b.c6m),
              ret(b.c0, b.c1y)));
    }
    if (rows.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no EQ bhavcopy accrued yet");
    }
    rows.sort((a, c) -> a.symbol().compareTo(c.symbol()));
    LocalDate asOf =
        jdbc.queryForObject(
            "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series = 'EQ'", LocalDate.class);
    return new Returns(asOf, rows);
  }

  /** Percent return of the latest close vs a base close; {@code null} when the base is missing/zero. */
  private static BigDecimal ret(BigDecimal latest, BigDecimal base) {
    if (base == null || base.signum() == 0) {
      return null;
    }
    return latest.subtract(base).multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
  }

  private record Base(
      String symbol,
      BigDecimal c0,
      BigDecimal c1d,
      BigDecimal c1w,
      BigDecimal c1m,
      BigDecimal c6m,
      BigDecimal c1y) {}
}
