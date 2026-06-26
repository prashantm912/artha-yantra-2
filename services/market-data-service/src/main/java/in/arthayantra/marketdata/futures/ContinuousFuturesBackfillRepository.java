package in.arthayantra.marketdata.futures;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The expired-future roster for the historical CONT reconstruction (2b-E1). Reads {@code
 * expired_contracts} (the Upstox 1-yr backfill registry) for FUT legs of one underlying root — the
 * source of front-month segments that predate the currently-listed contracts in {@code instruments}.
 * Raw JDBC by design: it touches a shared table without importing the upstox module (no new Modulith
 * edge). Candle bars live in the {@code candles} hypertable, read through {@link
 * in.arthayantra.marketdata.candles.CandleRepository}.
 */
@Repository
public class ContinuousFuturesBackfillRepository {

  /** One expired future leg — the minimum the roll/stitch loop needs (symbol + expiry). */
  public record ExpiredFuture(String exchange, String tradingsymbol, LocalDate expiry) {}

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ContinuousFuturesBackfillRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Expired FUT legs for an underlying root (e.g. {@code NIFTY}), expiry-ascending. */
  public List<ExpiredFuture> futuresRoster(String underlyingRoot) {
    return jdbc.query(
        """
        SELECT exchange, tradingsymbol, expiry FROM expired_contracts
        WHERE underlying_symbol = ? AND instrument_type = 'FUT'
        ORDER BY expiry
        """,
        (rs, n) ->
            new ExpiredFuture(
                rs.getString("exchange"),
                rs.getString("tradingsymbol"),
                rs.getDate("expiry").toLocalDate()),
        underlyingRoot);
  }
}
