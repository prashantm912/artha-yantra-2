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
    // The window bases stay recency-ranked (rn 2/6/22/… = N sessions back), but DROP any symbol whose
    // latest row (rn=1, the LTP) isn't the max accrued session (audit D3/latestMapped): the HAVING
    // excludes thin/delisted names so a stale close never shows as "current" under the asOf() = same-max
    // badge. (The distinct rn-vs-calendar / 07-02-partial window-base finding is out of scope here.)
    //
    // CA-adjusted price plane (FID P0-4 / audit H6): each base close is multiplied by the cumulative
    // product of every eod_corporate_actions ratio whose ex_date falls AFTER that bar — the SAME
    // multiplicative rule the shared AdjustedEquityDailySql plane applies (exp(sum(ln(ratio))) LEFT
    // JOIN LATERAL, COALESCE 1 no-op for the ~99.9% non-CA universe), rounded to 4dp to match the
    // NUMERIC(18,4) close scale. WITHOUT it a split/bonus inside a window opened a false price cliff
    // that cratered the multi-day return (the raw pre-split close sits on a different price scale than
    // the post-split LTP). The lateral runs only on the ≤6 picked rows per symbol. Unlike the
    // single-day BreadthService/EquitySectorService reads (close_price vs the exchange-published,
    // already ex-adjusted prev_close — adjusting one side would double-count), this fold reconstructs
    // returns from actual historical closes, so it MUST ride the adjusted plane. round(close_price,4)
    // is a no-op for a non-CA name (factor 1 on NUMERIC(18,4)) → its output stays byte-identical.
    List<Base> bases =
        jdbc.query(
            "WITH ranked AS ("
                + "  SELECT symbol, close_price, trade_date, "
                + "    ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) AS rn "
                + "  FROM nse_eod_bhavcopy WHERE series = 'EQ'), "
                + "picked AS ("
                + "  SELECT symbol, close_price, trade_date, rn "
                + "  FROM ranked WHERE rn IN (1, 2, 6, 22, 127, 253)) "
                + "SELECT p.symbol, "
                + "  max(round(p.close_price * caf.factor, 4)) FILTER (WHERE rn = 1)   AS c0, "
                + "  max(round(p.close_price * caf.factor, 4)) FILTER (WHERE rn = 2)   AS c1d, "
                + "  max(round(p.close_price * caf.factor, 4)) FILTER (WHERE rn = 6)   AS c1w, "
                + "  max(round(p.close_price * caf.factor, 4)) FILTER (WHERE rn = 22)  AS c1m, "
                + "  max(round(p.close_price * caf.factor, 4)) FILTER (WHERE rn = 127) AS c6m, "
                + "  max(round(p.close_price * caf.factor, 4)) FILTER (WHERE rn = 253) AS c1y "
                + "FROM picked p "
                + "LEFT JOIN LATERAL ("
                + "  SELECT COALESCE(exp(sum(ln(ca.ratio))), 1) AS factor "
                + "  FROM eod_corporate_actions ca "
                + "  WHERE ca.exchange = 'NSE' AND ca.tradingsymbol = p.symbol "
                + "    AND ca.ex_date > p.trade_date"
                + ") caf ON true "
                + "GROUP BY p.symbol "
                + "HAVING max(p.trade_date) FILTER (WHERE rn = 1) "
                + "     = (SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series = 'EQ')",
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
