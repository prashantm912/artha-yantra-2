package in.arthayantra.marketdata.upstox;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC for the expired-instruments backfill: registers an expired contract (so the backtest engine
 * and the OI pages can resolve an expired tradingsymbol → strike/expiry/lot) plus its coverage, and
 * answers the {@link Coverage} state that makes a re-run resumable AND gap-aware. Candle rows
 * themselves are written through the shared {@code CandleRepository}.
 */
@Repository
public class ExpiredBackfillRepository {

  /** Registry coverage for the resume key: absent / present-but-short / present-and-complete. */
  public enum Coverage {
    NONE,
    PARTIAL,
    COMPLETE
  }

  private static final String UPSERT =
      """
      INSERT INTO expired_contracts
        (exchange, tradingsymbol, instrument_type, underlying_symbol, expiry, strike,
         lot_size, tick_size, weekly, upstox_key, underlying_key,
         first_bucket, last_bucket, bar_count, complete)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT (exchange, tradingsymbol) DO UPDATE SET
        lot_size = EXCLUDED.lot_size,
        tick_size = EXCLUDED.tick_size,
        upstox_key = EXCLUDED.upstox_key,
        first_bucket = EXCLUDED.first_bucket,
        last_bucket = EXCLUDED.last_bucket,
        bar_count = EXCLUDED.bar_count,
        complete = EXCLUDED.complete
      """;

  private final JdbcTemplate jdbc;

  /** Wires the marketdata datasource. */
  public ExpiredBackfillRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Registers (idempotently) one expired contract with its fetched coverage. */
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
      String underlyingKey,
      OffsetDateTime firstBucket,
      OffsetDateTime lastBucket,
      int barCount,
      boolean complete) {
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
        underlyingKey,
        firstBucket == null ? null : Timestamp.from(firstBucket.toInstant()),
        lastBucket == null ? null : Timestamp.from(lastBucket.toInstant()),
        barCount,
        complete);
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
   * The registry coverage for a contract — the resume key. {@code NONE} = never registered (a crashed
   * contract stays here, since registration is AFTER the candle write → it is retried). {@code
   * PARTIAL} = registered but short (a transient false-empty left a hole) → re-fetch once. {@code
   * COMPLETE} = fetched through to the liquid expiry tail (or accepted after a re-fetch) → skip.
   */
  public Coverage coverage(String exchange, String tradingsymbol) {
    Boolean complete =
        jdbc.query(
            "SELECT complete FROM expired_contracts WHERE exchange = ? AND tradingsymbol = ?",
            rs -> rs.next() ? rs.getBoolean("complete") : null,
            exchange,
            tradingsymbol);
    if (complete == null) {
      return Coverage.NONE;
    }
    return complete ? Coverage.COMPLETE : Coverage.PARTIAL;
  }
}
