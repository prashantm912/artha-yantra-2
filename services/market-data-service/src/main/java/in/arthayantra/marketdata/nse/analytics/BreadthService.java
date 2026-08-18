package in.arthayantra.marketdata.nse.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.equitydaily.CashEquityUniverse;
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
 * oipulse "Breadth": advance/decline counts + delivery%-leaders from the NSE cash-equity bhavcopy
 * (V014) for a single trade date. 422 DATA_GAP when no bhavcopy exists for that date.
 *
 * <p>⚠️ The population is the EQ+BE cash universe ({@link CashEquityUniverse}), not EQ alone — H24
 * PR-3. All three reads move together on purpose: the summary, its {@code max(fetched_at)} freshness
 * pin, and the delivery leaderboard. <b>Widening a population without widening its pin is the
 * mixed-watermark defect</b> ({@code EquityBreadthEodJob:82} + {@code DataQualityEodJob:103} are
 * H24's named instance of it), so {@code :83} is not optional cleanup — it is half the fix.
 *
 * <p>This is a DISPLAY surface: the only consumer is {@code BreadthController} {@code GET
 * /api/v1/market/breadth?date=}. It is <b>not</b> the scalper breadth dot — that reads
 * {@code /breadth/live}, an intraday ~50-name NIFTY-constituent fold served by
 * {@code EquityIndexContributionService} ({@code BreadthController:63-68} explains why a
 * full-bhavcopy date read can never express the §12.3 "advances &gt; 32" rule intraday).
 *
 * <p>The measured before/after for the session this shipped on lives in the PR, not here — a
 * javadoc that restates one session's counts reads as stale within weeks.
 *
 * <p>⚠️ Two of the three reads do NOT move numerically, and the reasons differ — worth stating
 * because H24's plan predicted one of them and got the other backwards:
 *
 * <ul>
 *   <li><b>{@code avg(deliv_per)} is byte-identical</b> (58.7676 before and after) because SQL
 *       {@code avg()} ignores NULLs and NSE publishes no delivery figures for BE. Predicted.
 *   <li><b>The delivery leaderboard is unchanged</b> because {@code :92} <i>already</i> carries
 *       {@code AND deliv_per IS NOT NULL}, which excludes every BE row on its own. H24's plan called
 *       for adding a NULL guard here to stop 250 empty rows landing at the TOP of an
 *       {@code ORDER BY deliv_per DESC} (Postgres sorts NULLs FIRST in DESC) — the hazard is real,
 *       but the guard predates this change, so the site was never exposed. The widening is kept
 *       anyway: it is correct the day NSE publishes a delivery figure for a BE name, and it keeps
 *       all three reads on one population rather than leaving a lone EQ-only filter to be
 *       misread later as deliberate.
 *   <li><b>Interim divergence, by sequencing.</b> Until H24 PR-6 converts
 *       {@code EquityBreadthDailyRepository:48}, the breadth PAGE shows this EQ+BE summary above an
 *       EQ-only history chart drawn from {@code equity_breadth_daily}. The page self-describes
 *       (its chart caption still says "from the EQ bhavcopy") and PR-3..6 deploy as ONE batch, so
 *       the mismatch exists between commits and never in a running stack — but it is real in the
 *       repo and is the reason PR-6 is not optional.
 * </ul>
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
      @Schema(type = "string", types = {"string", "null"}) BigDecimal avgDeliveryPct) {}

  public record DeliveryRow(
      String symbol,
      @Schema(type = "string") BigDecimal deliveryPct,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal close,
      @Schema(type = "string", types = {"string", "null"}) BigDecimal pctChange) {}

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
                + "FROM nse_eod_bhavcopy WHERE trade_date = ? AND " + CashEquityUniverse.SERIES_PREDICATE,
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
            "SELECT max(fetched_at) FROM nse_eod_bhavcopy WHERE trade_date = ? AND "
                + CashEquityUniverse.SERIES_PREDICATE,
            (rs, n) -> rs.getObject(1, OffsetDateTime.class),
            java.sql.Date.valueOf(date));
    List<DeliveryRow> top =
        jdbc.query(
            "SELECT symbol, deliv_per, close_price, "
                + " CASE WHEN prev_close > 0 THEN (close_price - prev_close) * 100 / prev_close END "
                + "   AS pct_change "
                + "FROM nse_eod_bhavcopy "
                + "WHERE trade_date = ? AND " + CashEquityUniverse.SERIES_PREDICATE
                + " AND deliv_per IS NOT NULL "
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
