package in.arthayantra.marketdata.fundamentals;

import java.sql.Date;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Upsert + read of the per-symbol {@code equity_fundamentals} snapshot (V032). */
@Repository
public class EquityFundamentalsRepository {

  private static final String UPSERT =
      """
      INSERT INTO equity_fundamentals
        (symbol, isin, market_cap_cr, free_float_mcap_cr, free_float_pct, promoter_pct, pe, roe,
         net_profit_cr, revenue_cr, revenue_prev_cr, as_of, source, fetched_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'UPSTOX', now())
      ON CONFLICT (symbol) DO UPDATE SET
        isin = EXCLUDED.isin, market_cap_cr = EXCLUDED.market_cap_cr,
        free_float_mcap_cr = EXCLUDED.free_float_mcap_cr, free_float_pct = EXCLUDED.free_float_pct,
        promoter_pct = EXCLUDED.promoter_pct, pe = EXCLUDED.pe, roe = EXCLUDED.roe,
        net_profit_cr = EXCLUDED.net_profit_cr, revenue_cr = EXCLUDED.revenue_cr,
        revenue_prev_cr = EXCLUDED.revenue_prev_cr, as_of = EXCLUDED.as_of, fetched_at = now()
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public EquityFundamentalsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Upserts one snapshot. Returns rows affected. */
  public int upsert(EquityFundamentals f) {
    return jdbc.update(
        UPSERT,
        f.symbol(), f.isin(), f.marketCapCr(), f.freeFloatMcapCr(), f.freeFloatPct(),
        f.promoterPct(), f.pe(), f.roe(), f.netProfitCr(), f.revenueCr(), f.revenuePrevCr(),
        f.asOf() == null ? null : Date.valueOf(f.asOf()));
  }

  /** Reads one symbol's snapshot. */
  public Optional<EquityFundamentals> find(String symbol) {
    return jdbc
        .query(
            "SELECT * FROM equity_fundamentals WHERE symbol = ?",
            (rs, n) ->
                new EquityFundamentals(
                    rs.getString("symbol"), rs.getString("isin"), rs.getBigDecimal("market_cap_cr"),
                    rs.getBigDecimal("free_float_mcap_cr"), rs.getBigDecimal("free_float_pct"),
                    rs.getBigDecimal("promoter_pct"), rs.getBigDecimal("pe"), rs.getBigDecimal("roe"),
                    rs.getBigDecimal("net_profit_cr"), rs.getBigDecimal("revenue_cr"),
                    rs.getBigDecimal("revenue_prev_cr"),
                    rs.getObject("as_of", java.time.LocalDate.class)),
            symbol)
        .stream()
        .findFirst();
  }

  /** How many symbols have a fundamentals snapshot. */
  public int count() {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM equity_fundamentals", Integer.class);
    return n == null ? 0 : n;
  }
}
