package in.arthayantra.marketdata.candles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read/write for {@code eod_corporate_actions} — the split/bonus ratios used by the read-time
 * {@link EquitySplitBonusAdjuster}. Lives in the candles module (alongside {@link RollEventsRepository}
 * for the futures-roll adjuster) so the read path has no dependency on the bhavcopy ingest module.
 */
@Repository
public class EodCorporateActionRepository {

  private final JdbcTemplate jdbc;

  public EodCorporateActionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A pre-ex-date price multiplier effective on {@code exDate}. */
  public record Ratio(LocalDate exDate, BigDecimal ratio) {}

  /** Idempotent upsert of one corporate action keyed by {@code (exchange, tradingsymbol, ex_date)}. */
  public void upsert(
      String exchange,
      String tradingsymbol,
      LocalDate exDate,
      BigDecimal ratio,
      String kind,
      String isin,
      String subject) {
    jdbc.update(
        """
        INSERT INTO eod_corporate_actions
          (exchange, tradingsymbol, ex_date, ratio, kind, isin, subject, source, detected_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'NSE_CA_API', now())
        ON CONFLICT (exchange, tradingsymbol, ex_date) DO UPDATE SET
          ratio = EXCLUDED.ratio,
          kind = EXCLUDED.kind,
          isin = EXCLUDED.isin,
          subject = EXCLUDED.subject,
          source = EXCLUDED.source
        """,
        exchange, tradingsymbol, exDate, ratio, kind, isin, subject);
  }

  /** All split/bonus ratios for a symbol, oldest ex-date first (the adjuster walks these). */
  public List<Ratio> ratiosFor(String exchange, String tradingsymbol) {
    return jdbc.query(
        "SELECT ex_date, ratio FROM eod_corporate_actions"
            + " WHERE exchange = ? AND tradingsymbol = ? ORDER BY ex_date",
        (rs, n) -> new Ratio(rs.getObject("ex_date", LocalDate.class), rs.getBigDecimal("ratio")),
        exchange, tradingsymbol);
  }

  /** The most-recent BSE ticker carrying an ISIN — maps an NSE-keyed action onto the BSE listing. */
  public String bseTickerForIsin(String isin) {
    if (isin == null || isin.isBlank()) {
      return null;
    }
    List<String> hit =
        jdbc.query(
            "SELECT ticker FROM bse_eod_bhavcopy WHERE isin = ? AND ticker IS NOT NULL"
                + " ORDER BY trade_date DESC LIMIT 1",
            (rs, n) -> rs.getString(1),
            isin);
    return hit.isEmpty() ? null : hit.get(0);
  }
}
