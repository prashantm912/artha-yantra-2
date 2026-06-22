package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of FII-derivative net rows by {@code (trade_date, segment)}. */
@Repository
public class NseEodFiiDerivativeRepository {

  private final JdbcTemplate jdbc;

  public NseEodFiiDerivativeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertAll(List<FiiDerivativeFetcher.FiiDerivativeRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO fii_derivative_stats (trade_date, segment, buy_value, sell_value, net_value, fetched_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, segment) DO UPDATE SET
          buy_value = EXCLUDED.buy_value,
          sell_value = EXCLUDED.sell_value,
          net_value = EXCLUDED.net_value,
          fetched_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.segment());
          ps.setBigDecimal(3, r.buy());
          ps.setBigDecimal(4, r.sell());
          ps.setBigDecimal(5, r.net());
        });
  }
}
