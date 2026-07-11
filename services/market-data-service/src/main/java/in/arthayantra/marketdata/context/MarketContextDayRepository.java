package in.arthayantra.marketdata.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writer for {@code marketdata.market_context_days} (intelligence-layer design §6.6) — the one new
 * persisted table the layer adds on the market-data side. One row per IST session day (trade_date PK):
 * the full serialized day-context plus the primary-index EOD baseline scalars. {@link #upsert} is an
 * accumulating {@code ON CONFLICT} so a re-run (manual or a retry) is idempotent.
 */
@Repository
public class MarketContextDayRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  /** Wires the JDBC template + the JSON mapper (for the day_context JSONB column). */
  public MarketContextDayRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Upsert one session-day row. {@code tradeDate} is the IST session date (never a UTC {@code ::date}).
   * Returns the affected-row count (1) — the {@code rows_written} the EOD job records in {@code
   * ingest_runs}.
   */
  public int upsert(
      LocalDate tradeDate,
      String optionsName,
      LocalDate expiry,
      BigDecimal pcrEod,
      BigDecimal maxPainEod,
      BigDecimal atmStraddleEod,
      BigDecimal atmIvEod,
      Object dayContext) {
    return jdbc.update(
        "INSERT INTO market_context_days"
            + " (trade_date, options_name, expiry, pcr_eod, max_pain_eod, atm_straddle_eod,"
            + "  atm_iv_eod, day_context)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
            // created_at is deliberately NOT in the update set — it keeps first-write semantics.
            + " ON CONFLICT (trade_date) DO UPDATE SET"
            + "  options_name = EXCLUDED.options_name, expiry = EXCLUDED.expiry,"
            + "  pcr_eod = EXCLUDED.pcr_eod, max_pain_eod = EXCLUDED.max_pain_eod,"
            + "  atm_straddle_eod = EXCLUDED.atm_straddle_eod, atm_iv_eod = EXCLUDED.atm_iv_eod,"
            + "  day_context = EXCLUDED.day_context",
        java.sql.Date.valueOf(tradeDate),
        optionsName,
        expiry == null ? null : java.sql.Date.valueOf(expiry),
        pcrEod,
        maxPainEod,
        atmStraddleEod,
        atmIvEod,
        json(dayContext));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      // day_context is the §10 replay fixture — silently persisting "{}" would corrupt it while the
      // ingest ledger records SUCCESS. Fail loudly instead: the EOD job's FAILURE path records the
      // error in ingest_runs and rethrows, so the hole is visible, never fabricated.
      throw new IllegalStateException("day_context serialization failed", e);
    }
  }
}
