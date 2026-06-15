package in.arthayantra.marketdata.nse;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotent upsert of NSE FII/DII rows by {@code (trade_date, category)}. */
@Repository
public class NseEodFiiDiiRepository {

  private final JdbcTemplate jdbc;

  public NseEodFiiDiiRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertAll(List<FiiDiiFetcher.FiiDiiRow> rows) {
    jdbc.batchUpdate(
        """
        INSERT INTO nse_eod_fii_dii (trade_date, category, buy_value, sell_value, net_value, fetched_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (trade_date, category) DO UPDATE SET
          buy_value = EXCLUDED.buy_value,
          sell_value = EXCLUDED.sell_value,
          net_value = EXCLUDED.net_value,
          fetched_at = now()
        """,
        rows,
        rows.size(),
        (ps, r) -> {
          ps.setObject(1, r.date());
          ps.setString(2, r.category());
          ps.setBigDecimal(3, r.buy());
          ps.setBigDecimal(4, r.sell());
          ps.setBigDecimal(5, r.net());
        });
  }
}
