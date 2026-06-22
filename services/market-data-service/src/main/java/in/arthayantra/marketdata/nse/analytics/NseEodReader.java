package in.arthayantra.marketdata.nse.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read layer over the NSE EOD tables (write side: the {@code nse} ingest repositories). Index-level,
 * date-ranged: FII/DII cash (V012), participant-wise OI (V013) and FII-derivative net stats (V024,
 * Upstox-sourced — ADR-0002 U6).
 */
@Repository
public class NseEodReader {

  private final JdbcTemplate jdbc;

  public NseEodReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record FiiDiiRow(
      LocalDate tradeDate,
      String category,
      BigDecimal buyValue,
      BigDecimal sellValue,
      BigDecimal netValue) {}

  public record ParticipantOiRow(
      LocalDate tradeDate,
      String clientType,
      long futureIndexLong,
      long futureIndexShort,
      long futureStockLong,
      long futureStockShort,
      long optionIndexCallLong,
      long optionIndexPutLong,
      long optionIndexCallShort,
      long optionIndexPutShort,
      long optionStockCallLong,
      long optionStockPutLong,
      long optionStockCallShort,
      long optionStockPutShort,
      long totalLongContracts,
      long totalShortContracts) {}

  public record FiiDerivativeRow(
      LocalDate tradeDate,
      String segment,
      BigDecimal buyValue,
      BigDecimal sellValue,
      BigDecimal netValue) {}

  public List<FiiDerivativeRow> fiiDerivativeStats(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT trade_date, segment, buy_value, sell_value, net_value "
            + "FROM fii_derivative_stats WHERE trade_date BETWEEN ? AND ? "
            + "ORDER BY trade_date, segment",
        (rs, n) ->
            new FiiDerivativeRow(
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("segment"),
                rs.getBigDecimal("buy_value"),
                rs.getBigDecimal("sell_value"),
                rs.getBigDecimal("net_value")),
        java.sql.Date.valueOf(from),
        java.sql.Date.valueOf(to));
  }

  public List<FiiDiiRow> fiiDii(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT trade_date, category, buy_value, sell_value, net_value "
            + "FROM nse_eod_fii_dii WHERE trade_date BETWEEN ? AND ? "
            + "ORDER BY trade_date, category",
        (rs, n) ->
            new FiiDiiRow(
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("category"),
                rs.getBigDecimal("buy_value"),
                rs.getBigDecimal("sell_value"),
                rs.getBigDecimal("net_value")),
        java.sql.Date.valueOf(from),
        java.sql.Date.valueOf(to));
  }

  public List<ParticipantOiRow> participantOi(LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT * FROM nse_eod_participant_oi WHERE trade_date BETWEEN ? AND ? "
            + "ORDER BY trade_date, client_type",
        (rs, n) ->
            new ParticipantOiRow(
                rs.getObject("trade_date", LocalDate.class),
                rs.getString("client_type"),
                rs.getLong("future_index_long"),
                rs.getLong("future_index_short"),
                rs.getLong("future_stock_long"),
                rs.getLong("future_stock_short"),
                rs.getLong("option_index_call_long"),
                rs.getLong("option_index_put_long"),
                rs.getLong("option_index_call_short"),
                rs.getLong("option_index_put_short"),
                rs.getLong("option_stock_call_long"),
                rs.getLong("option_stock_put_long"),
                rs.getLong("option_stock_call_short"),
                rs.getLong("option_stock_put_short"),
                rs.getLong("total_long_contracts"),
                rs.getLong("total_short_contracts")),
        java.sql.Date.valueOf(from),
        java.sql.Date.valueOf(to));
  }
}
