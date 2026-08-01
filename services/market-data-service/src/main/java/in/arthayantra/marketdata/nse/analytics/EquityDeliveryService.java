package in.arthayantra.marketdata.nse.analytics;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * oipulse "Delivery Data": one stock's daily delivery quantity / % time-series over a relative period
 * (the most recent N trading sessions) from the NSE EQ-series bhavcopy. % delivery is the institutional
 * conviction read; % LTP change and the day range ride along. 422 DATA_GAP when the symbol has no rows.
 */
@Service
public class EquityDeliveryService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int MAX_DAYS = 180;

  private final JdbcTemplate jdbc;

  public EquityDeliveryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** One trading day's delivery row; {@code dayRange = high − low}, {@code dayRangePct = range/close}. */
  public record DeliveryDay(
      LocalDate date,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal open,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal high,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal low,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal close,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal ltpChangePct,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal deliveryPct,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal dayRange,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal dayRangePct,
      @Schema(types = {"integer", "null"}) Long deliveryQty,
      @Schema(types = {"integer", "null"}) Long totalTradedQty) {}

  /** A symbol's delivery series, newest first. */
  public record Delivery(String symbol, int days, List<DeliveryDay> items) {}

  public Delivery delivery(String symbolRaw, int daysRaw) {
    String symbol = symbolRaw.trim().toUpperCase();
    int days = Math.max(1, Math.min(MAX_DAYS, daysRaw));
    List<DeliveryDay> rows =
        jdbc.query(
            "SELECT trade_date, open_price, high_price, low_price, close_price, prev_close, "
                + " deliv_per, deliv_qty, ttl_trd_qnty "
                + "FROM nse_eod_bhavcopy "
                + "WHERE symbol = ? AND series = 'EQ' "
                + "ORDER BY trade_date DESC LIMIT ?",
            (rs, n) -> mapRow(rs),
            symbol,
            days);
    if (rows.isEmpty()) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no EQ bhavcopy for symbol " + symbol);
    }
    return new Delivery(symbol, days, rows);
  }

  private static DeliveryDay mapRow(ResultSet rs) throws SQLException {
    BigDecimal high = rs.getBigDecimal("high_price");
    BigDecimal low = rs.getBigDecimal("low_price");
    BigDecimal close = rs.getBigDecimal("close_price");
    BigDecimal prevClose = rs.getBigDecimal("prev_close");
    BigDecimal range = (high != null && low != null) ? high.subtract(low) : null;
    BigDecimal ltpChangePct =
        (prevClose != null && prevClose.signum() > 0 && close != null)
            ? close.subtract(prevClose).multiply(HUNDRED).divide(prevClose, 2, RoundingMode.HALF_UP)
            : null;
    BigDecimal dayRangePct =
        (range != null && close != null && close.signum() > 0)
            ? range.multiply(HUNDRED).divide(close, 2, RoundingMode.HALF_UP)
            : null;
    return new DeliveryDay(
        rs.getObject("trade_date", LocalDate.class),
        rs.getBigDecimal("open_price"),
        high,
        low,
        close,
        ltpChangePct,
        rs.getBigDecimal("deliv_per"),
        range,
        dayRangePct,
        longOrNull(rs, "deliv_qty"),
        longOrNull(rs, "ttl_trd_qnty"));
  }

  private static Long longOrNull(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }
}
