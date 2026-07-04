package in.arthayantra.marketdata.screener.minervini;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The SEPA funnel (MV-6.7, §2.10 triad): ranks the day's Trend-Template passers into the actionable
 * three-list — <b>immediately buyable</b> / <b>on deck</b> / <b>watch</b> — by joining the persisted
 * screen ({@code minervini_screen_results}: the 8-gate pass + RS-rank) with the Phase-5 base geometry
 * ({@code minervini_setups}: a valid VCP base + its pivot). Convergence, not a new signal: a passer
 * with a valid VCP whose price sits AT the pivot is immediately buyable; one still tightening toward
 * the pivot from below is on-deck; a trend-template passer with no valid base (or extended past the
 * pivot) is a watch. Each bucket is ranked by RS-rank. Pure SQL over already-persisted rows.
 *
 * <p>Bands are config-tunable ({@code artha.minervini.funnel.*}); expressed as fractions of the pivot.
 */
@Service
public class MinerviniFunnelService {

  /** One funnel candidate: the screen numbers + the geometry + its distance to the pivot. */
  public record FunnelRow(
      String symbol,
      BigDecimal close,
      BigDecimal rsRank,
      Integer stage,
      boolean isVcp,
      BigDecimal pivot,
      String footprint,
      BigDecimal pctToPivot) {} // (close - pivot) / pivot, null when no pivot

  /** The three-list for a screen date + the market regime (MV-6.9) the owner should buy WITH. */
  public record Funnel(
      LocalDate screenDate,
      RegimeService.Regime regime,
      List<FunnelRow> immediatelyBuyable,
      List<FunnelRow> onDeck,
      List<FunnelRow> watch) {}

  private static final String SQL =
      """
      SELECT r.symbol, r.close_price, r.rs_rank, r.stage,
             COALESCE(s.is_vcp, FALSE) AS is_vcp, s.pivot, s.footprint
      FROM minervini_screen_results r
      LEFT JOIN minervini_setups s
        ON s.screen_date = r.screen_date AND s.symbol = r.symbol
      WHERE r.screen_date = ? AND r.passes_all = TRUE
      ORDER BY r.rs_rank DESC NULLS LAST
      """;

  private final JdbcTemplate jdbc;
  private final RegimeService regimeService;
  private final BigDecimal buyableLow; // close >= pivot * buyableLow  (at/just below the pivot)
  private final BigDecimal buyableHigh; // close <= pivot * buyableHigh (not chased past it)
  private final BigDecimal onDeckFloor; // close >= pivot * onDeckFloor (tightening toward the pivot)

  /** Wires the marketdata datasource + regime + the config-tunable funnel bands (fractions of the pivot). */
  public MinerviniFunnelService(
      JdbcTemplate jdbc,
      RegimeService regimeService,
      @Value("${artha.minervini.funnel.buyable-low:0.98}") BigDecimal buyableLow,
      @Value("${artha.minervini.funnel.buyable-high:1.05}") BigDecimal buyableHigh,
      @Value("${artha.minervini.funnel.on-deck-floor:0.90}") BigDecimal onDeckFloor) {
    this.jdbc = jdbc;
    this.regimeService = regimeService;
    this.buyableLow = buyableLow;
    this.buyableHigh = buyableHigh;
    this.onDeckFloor = onDeckFloor;
  }

  /** Builds the three-list as of {@code screenDate}. */
  public Funnel funnel(LocalDate screenDate) {
    List<FunnelRow> passers =
        jdbc.query(
            SQL,
            (rs, n) -> {
              BigDecimal close = rs.getBigDecimal("close_price");
              BigDecimal pivot = rs.getBigDecimal("pivot");
              BigDecimal pct =
                  (pivot == null || pivot.signum() == 0)
                      ? null
                      : close.subtract(pivot).divide(pivot, 4, RoundingMode.HALF_UP);
              return new FunnelRow(
                  rs.getString("symbol"), close, rs.getBigDecimal("rs_rank"),
                  rs.getObject("stage", Integer.class), rs.getBoolean("is_vcp"), pivot,
                  rs.getString("footprint"), pct);
            },
            java.sql.Date.valueOf(screenDate));

    List<FunnelRow> buyable = new ArrayList<>();
    List<FunnelRow> onDeck = new ArrayList<>();
    List<FunnelRow> watch = new ArrayList<>();
    for (FunnelRow row : passers) {
      switch (bucket(row)) {
        case BUYABLE -> buyable.add(row);
        case ON_DECK -> onDeck.add(row);
        default -> watch.add(row);
      }
    }
    return new Funnel(screenDate, regimeService.regime(screenDate), buyable, onDeck, watch);
  }

  private enum Bucket {
    BUYABLE,
    ON_DECK,
    WATCH
  }

  /**
   * A valid VCP base with price in [pivot·buyableLow, pivot·buyableHigh] is immediately buyable (at
   * the breakout, not chased); in [pivot·onDeckFloor, pivot·buyableLow) it is on-deck (tightening
   * toward the pivot); everything else — no valid base, extended past the pivot, or well below it —
   * is a watch.
   */
  private Bucket bucket(FunnelRow row) {
    if (!row.isVcp() || row.pivot() == null || row.pivot().signum() == 0 || row.close() == null) {
      return Bucket.WATCH;
    }
    BigDecimal pivot = row.pivot();
    BigDecimal close = row.close();
    if (close.compareTo(pivot.multiply(buyableLow)) >= 0
        && close.compareTo(pivot.multiply(buyableHigh)) <= 0) {
      return Bucket.BUYABLE;
    }
    if (close.compareTo(pivot.multiply(onDeckFloor)) >= 0
        && close.compareTo(pivot.multiply(buyableLow)) < 0) {
      return Bucket.ON_DECK;
    }
    return Bucket.WATCH;
  }
}
