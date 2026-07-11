package in.arthayantra.marketdata.context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * FII/DII-context folds for the FII digest (intelligence-layer design 2026-07-10 §6.1.4) — SQL over
 * the EXISTING {@code nse_eod_fii_dii} (cash), {@code nse_eod_participant_oi} (index-futures
 * long/short positioning), and {@code fii_derivative_stats} (Upstox-analytics-only derivative net)
 * tables, no new raw data (§13 row 4). These reads live in the {@code context} module rather than
 * injecting {@code NseEodReader} (another module's internal {@code nse.analytics} type — cross-module
 * deps target base-package types only here).
 *
 * <p>{@code fii_derivative_stats} is the one source that is legitimately empty when
 * {@code artha.upstox.analytics.enabled=false} (no fetcher bean, no ingest run) — the digest
 * trust-gates it against {@code ingest_runs} rather than treating the empty table as a failure.
 */
@Repository
public class FiiContextRepository {

  private final JdbcTemplate jdbc;

  public FiiContextRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The latest FII/DII cash session (null when the table is empty). */
  public LocalDate latestCashSession() {
    return jdbc.queryForObject("SELECT max(trade_date) FROM nse_eod_fii_dii", LocalDate.class);
  }

  /** One FII/DII cash row (category = {@code 'FII/FPI'} or {@code 'DII'}). */
  public record CashRow(LocalDate tradeDate, String category, BigDecimal net) {}

  /** The last {@code limit} cash sessions on/before {@code date}, newest first (both categories). */
  public List<CashRow> cashSeries(LocalDate date, int limit) {
    return jdbc.query(
        "SELECT trade_date, category, net_value FROM nse_eod_fii_dii"
            + " WHERE trade_date <= ? ORDER BY trade_date DESC, category LIMIT ?",
        (rs, n) ->
            new CashRow(
                rs.getObject("trade_date", LocalDate.class), rs.getString("category"), rs.getBigDecimal("net_value")),
        java.sql.Date.valueOf(date),
        limit);
  }

  /** One FII participant-OI row (index-futures long/short + total contracts). */
  public record ParticipantRow(
      LocalDate tradeDate, long indexLong, long indexShort, long totalLong, long totalShort) {}

  /** The last {@code limit} FII participant-OI sessions on/before {@code date}, newest first. */
  public List<ParticipantRow> fiiParticipantSeries(LocalDate date, int limit) {
    return jdbc.query(
        "SELECT trade_date, future_index_long, future_index_short, total_long_contracts, total_short_contracts"
            + " FROM nse_eod_participant_oi WHERE client_type = 'FII' AND trade_date <= ?"
            + " ORDER BY trade_date DESC LIMIT ?",
        (rs, n) ->
            new ParticipantRow(
                rs.getObject("trade_date", LocalDate.class),
                rs.getLong("future_index_long"),
                rs.getLong("future_index_short"),
                rs.getLong("total_long_contracts"),
                rs.getLong("total_short_contracts")),
        java.sql.Date.valueOf(date),
        limit);
  }

  /** One FII index-futures derivative-stats row (net value, Upstox analytics only). */
  public record DerivativeRow(LocalDate tradeDate, BigDecimal net) {}

  /** The last {@code limit} FII INDEX_FUTURES derivative sessions on/before {@code date}, newest first. */
  public List<DerivativeRow> indexFuturesSeries(LocalDate date, int limit) {
    return jdbc.query(
        "SELECT trade_date, net_value FROM fii_derivative_stats"
            + " WHERE segment = 'INDEX_FUTURES' AND trade_date <= ? ORDER BY trade_date DESC LIMIT ?",
        (rs, n) -> new DerivativeRow(rs.getObject("trade_date", LocalDate.class), rs.getBigDecimal("net_value")),
        java.sql.Date.valueOf(date),
        limit);
  }
}
