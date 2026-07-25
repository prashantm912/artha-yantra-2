package in.arthayantra.marketdata.nse.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.freshness.DataFreshness;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * oipulse "Breadth": advance/decline counts + delivery%-leaders from the NSE EQ-series bhavcopy
 * (V014) for a single trade date. 422 DATA_GAP when no bhavcopy exists for that date.
 */
@Service
public class BreadthService {

  private final JdbcTemplate jdbc;
  private final int topN;

  public BreadthService(JdbcTemplate jdbc, @Value("${artha.breadth.top-n:20}") int topN) {
    this.jdbc = jdbc;
    this.topN = topN;
  }

  public record BreadthSummary(
      LocalDate tradeDate,
      int advances,
      int declines,
      int unchanged,
      int total,
      @Schema(types = {"number", "null"}) BigDecimal avgDeliveryPct) {}

  public record DeliveryRow(
      String symbol,
      BigDecimal deliveryPct,
      @Schema(types = {"number", "null"}) BigDecimal close,
      @Schema(types = {"number", "null"}) BigDecimal pctChange) {}

  public record Breadth(
      BreadthSummary summary,
      List<DeliveryRow> topDelivery,
      OffsetDateTime asOf,
      @JsonInclude(JsonInclude.Include.NON_NULL) DataFreshness freshness) {
    public Breadth(BreadthSummary summary, List<DeliveryRow> topDelivery, OffsetDateTime asOf) {
      this(summary, topDelivery, asOf, null);
    }

    /** Returns a copy carrying the freshness envelope (populated at the controller boundary). */
    public Breadth withFreshness(DataFreshness f) {
      return new Breadth(summary, topDelivery, asOf, f);
    }
  }

  public Breadth breadth(LocalDate date) {
    BreadthSummary summary =
        jdbc.queryForObject(
            "SELECT "
                + " count(*) FILTER (WHERE close_price > prev_close) AS adv, "
                + " count(*) FILTER (WHERE close_price < prev_close) AS dec, "
                + " count(*) FILTER (WHERE close_price = prev_close) AS unch, "
                + " count(*) AS total, avg(deliv_per) AS avg_deliv "
                + "FROM nse_eod_bhavcopy WHERE trade_date = ? AND series = 'EQ'",
            (rs, n) ->
                new BreadthSummary(
                    date,
                    rs.getInt("adv"),
                    rs.getInt("dec"),
                    rs.getInt("unch"),
                    rs.getInt("total"),
                    rs.getBigDecimal("avg_deliv")),
            java.sql.Date.valueOf(date));
    if (summary == null || summary.total() == 0) {
      throw new ApiException(422, ErrorCodes.DATA_GAP, "no bhavcopy for " + date);
    }
    OffsetDateTime asOf =
        jdbc.queryForObject(
            "SELECT max(fetched_at) FROM nse_eod_bhavcopy WHERE trade_date = ? AND series = 'EQ'",
            (rs, n) -> rs.getObject(1, OffsetDateTime.class),
            java.sql.Date.valueOf(date));
    List<DeliveryRow> top =
        jdbc.query(
            "SELECT symbol, deliv_per, close_price, "
                + " CASE WHEN prev_close > 0 THEN (close_price - prev_close) * 100 / prev_close END "
                + "   AS pct_change "
                + "FROM nse_eod_bhavcopy "
                + "WHERE trade_date = ? AND series = 'EQ' AND deliv_per IS NOT NULL "
                + "ORDER BY deliv_per DESC LIMIT ?",
            (rs, n) ->
                new DeliveryRow(
                    rs.getString("symbol"),
                    rs.getBigDecimal("deliv_per"),
                    rs.getBigDecimal("close_price"),
                    rs.getBigDecimal("pct_change")),
            java.sql.Date.valueOf(date),
            topN);
    return new Breadth(summary, top, asOf);
  }
}
