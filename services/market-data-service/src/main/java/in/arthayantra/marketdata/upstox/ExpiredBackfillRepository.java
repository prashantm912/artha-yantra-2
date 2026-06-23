package in.arthayantra.marketdata.upstox;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the expired-instruments backfill: registers an expired contract (so the backtest engine
 * and the OI pages can resolve an expired tradingsymbol → strike/expiry/lot) and answers the
 * coverage check that makes a re-run resumable (skip a contract whose candles are already present).
 * Candle rows themselves are written through the shared {@code CandleRepository}.
 */
@Repository
public class ExpiredBackfillRepository {

  private static final String UPSERT =
      """
      INSERT INTO expired_contracts
        (exchange, tradingsymbol, instrument_type, underlying_symbol, expiry, strike,
         lot_size, tick_size, weekly, upstox_key, underlying_key)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT (exchange, tradingsymbol) DO UPDATE SET
        lot_size = EXCLUDED.lot_size,
        tick_size = EXCLUDED.tick_size,
        upstox_key = EXCLUDED.upstox_key
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ExpiredBackfillRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Registers (idempotently) one expired contract. */
  public void upsertContract(
      String exchange,
      String tradingsymbol,
      String instrumentType,
      String underlyingSymbol,
      LocalDate expiry,
      BigDecimal strike,
      int lotSize,
      BigDecimal tickSize,
      boolean weekly,
      String upstoxKey,
      String underlyingKey) {
    jdbc.update(
        UPSERT,
        exchange,
        tradingsymbol,
        instrumentType,
        underlyingSymbol,
        java.sql.Date.valueOf(expiry),
        strike,
        lotSize,
        tickSize,
        weekly,
        upstoxKey,
        underlyingKey);
  }

  /**
   * The underlying's [min low, max high] over its 1d candles in {@code [from, to)} — the reference for
   * the ±20% strike band. Returns {@code null} when no daily candles cover the window (the caller then
   * over-fetches rather than dropping everything).
   */
  public BigDecimal[] indexRange(
      String exchange, String tradingsymbol, LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT min(low) AS lo, max(high) AS hi FROM candles "
            + "WHERE exchange = ? AND tradingsymbol = ? AND \"interval\" = '1d' "
            + "AND bucket >= ? AND bucket < ?",
        rs -> {
          if (rs.next() && rs.getBigDecimal("lo") != null) {
            return new BigDecimal[] {rs.getBigDecimal("lo"), rs.getBigDecimal("hi")};
          }
          return null;
        },
        exchange,
        tradingsymbol,
        java.sql.Date.valueOf(from),
        java.sql.Date.valueOf(to));
  }

  /**
   * Whether a contract is already registered — the resumable-skip key. Registration happens only
   * AFTER its candles are fully upserted, so a contract whose candle batch was interrupted has rows
   * but no registry row and is correctly RE-fetched (the candle upsert is idempotent, so the gap
   * fills). Keying the skip on candle presence instead would permanently strand a half-written batch.
   */
  public boolean isRegistered(String exchange, String tradingsymbol) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM expired_contracts WHERE exchange = ? AND tradingsymbol = ?)",
            Boolean.class,
            exchange,
            tradingsymbol);
    return Boolean.TRUE.equals(exists);
  }
}
