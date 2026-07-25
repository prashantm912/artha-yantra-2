package in.arthayantra.marketdata.screener.manas;

import in.arthayantra.marketdata.screener.minervini.RegimeService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Manas actionable funnel: ranks the day's selection passers into <b>immediately buyable</b> /
 * <b>on deck</b> / <b>watch</b> by joining the persisted screen ({@code manas_arora_screen_results}:
 * the 6-gate + universe + liquidity pass) with the base geometry ({@code manas_arora_setups}: a valid
 * vcp/breakout base + its pivot). Convergence, not a new signal (§3.1 step 4): a passer with a valid
 * base whose price sits AT the pivot is immediately buyable (do NOT chase &gt;5% past it, §2.5); one
 * still tightening toward the pivot from below is on-deck; a passer with no valid base (or extended
 * past the pivot) is a watch. When both setups are valid, the nearest (highest) pivot at/below price
 * wins. Pure SQL over already-persisted rows. Mirrors the sibling {@code MinerviniFunnelService}.
 *
 * <p>Bands are config-tunable ({@code artha.manas-arora.funnel.*}); expressed as fractions of the pivot.
 */
@Service
public class ManasFunnelService {

  /**
   * One funnel candidate: the screen numbers + the chosen (BEST) setup + its distance to the pivot,
   * plus BOTH setups' pivots so the live engine can route each strategy to its own. {@code
   * setupType}/{@code pivot}/{@code pctToPivot} carry the BEST valid setup (side-aware nearest pivot)
   * and drive the buyable/on-deck/watch bucketing + the FE funnel page — UNCHANGED. {@code
   * breakoutPivot}/{@code vcpPivot} carry the valid pivot of each setup_type (null when that setup is
   * invalid/absent) so the breakout strategy fires off the §3.2 breakout pivot and the VCP strategy
   * off the §3.3 VCP pivot.
   */
  public record FunnelRow(
      String symbol,
      BigDecimal close,
      BigDecimal aboveLowPct,
      String setupType,
      BigDecimal pivot,
      String footprint,
      BigDecimal pctToPivot, // (close - pivot) / pivot, null when no pivot
      BigDecimal breakoutPivot, // §3.2 consolidation-breakout pivot, null when that setup is invalid
      BigDecimal vcpPivot, // §3.3 VCP-contraction pivot, null when that setup is invalid
      BigDecimal rsRank) {} // §4.1/§4.10 cross-sectional RS-rank (the admission sort key), null pre-rank

  /** The three-list for a screen date + the market regime (§4.9) the owner should buy WITH. */
  public record Funnel(
      @Schema(types = {"string", "null"}) LocalDate screenDate,
      RegimeService.Regime regime,
      List<FunnelRow> immediatelyBuyable,
      List<FunnelRow> onDeck,
      List<FunnelRow> watch) {}

  // One row per passer with its BEST valid setup (v: nearest pivot at/above the close, else any valid
  // pivot — drives the buyable/on-deck bucketing) AND each setup_type's own valid pivot side-channel
  // (b/vc) so the live engine can route the breakout strategy to the §3.2 pivot and the VCP strategy
  // to the §3.3 pivot. Each join is null when that setup is invalid/absent for the symbol.
  private static final String SQL =
      """
      SELECT r.symbol, r.close_price, r.above_low_pct, r.rs_rank,
             v.setup_type, v.pivot, v.footprint,
             b.pivot AS breakout_pivot, vc.pivot AS vcp_pivot
      FROM manas_arora_screen_results r
      LEFT JOIN LATERAL (
        SELECT s.setup_type, s.pivot, s.footprint
        FROM manas_arora_setups s
        WHERE s.screen_date = r.screen_date AND s.symbol = r.symbol
          AND s.is_valid = TRUE AND s.pivot IS NOT NULL
        -- Prefer the nearest pivot AT/ABOVE the close (the one price is approaching → the buyable/
        -- on-deck test uses it), falling back to the nearest below when both setups sit under price.
        ORDER BY (s.pivot >= r.close_price) DESC, abs(s.pivot - r.close_price) ASC
        LIMIT 1
      ) v ON TRUE
      LEFT JOIN manas_arora_setups b
        ON b.screen_date = r.screen_date AND b.symbol = r.symbol
          AND b.setup_type = 'breakout' AND b.is_valid = TRUE AND b.pivot IS NOT NULL
      LEFT JOIN manas_arora_setups vc
        ON vc.screen_date = r.screen_date AND vc.symbol = r.symbol
          AND vc.setup_type = 'vcp' AND vc.is_valid = TRUE AND vc.pivot IS NOT NULL
      WHERE r.screen_date = ? AND r.passes_all = TRUE
        -- §4.1/§4.10 RS-rank gate: only the top cross-sectional relative-strength names reach the book
        -- (the backtest's "rs" variant, the method validator). Config-tunable; a NULL rank (pre-migration
        -- / edge) is never dropped. funnel-rs-min=0 disables the gate.
        AND (r.rs_rank >= ? OR r.rs_rank IS NULL)
      -- RS-PRIORITY admission: the daily batch admits candidates in this order until the book's slots
      -- fill, so the highest-RS names get the scarce slots (matches the backtest's RS-priority portfolio).
      ORDER BY r.rs_rank DESC NULLS LAST, r.above_low_pct DESC NULLS LAST
      """;

  private final JdbcTemplate jdbc;
  private final RegimeService regimeService;
  private final BigDecimal buyableLow; // close >= pivot * buyableLow  (at/just below the pivot)
  private final BigDecimal buyableHigh; // close <= pivot * buyableHigh (not chased past it)
  private final BigDecimal onDeckFloor; // close >= pivot * onDeckFloor (tightening toward the pivot)
  private final BigDecimal funnelRsMin; // §4.10 RS-rank gate: only names with rs_rank >= this reach the book

  /** Wires the marketdata datasource + regime + the config-tunable funnel bands (fractions of the pivot). */
  public ManasFunnelService(
      JdbcTemplate jdbc,
      RegimeService regimeService,
      @Value("${artha.manas-arora.funnel.buyable-low:0.98}") BigDecimal buyableLow,
      @Value("${artha.manas-arora.funnel.buyable-high:1.05}") BigDecimal buyableHigh,
      @Value("${artha.manas-arora.funnel.on-deck-floor:0.90}") BigDecimal onDeckFloor,
      // Doctrine §4.1/§4.10: the cross-sectional RS-rank gate — "the single biggest edge a filter can
      // add" (the backtest's rs≥70 method-validator). Default 70; set 0 to disable.
      @Value("${artha.manas-arora.funnel-rs-min:70}") BigDecimal funnelRsMin) {
    this.jdbc = jdbc;
    this.regimeService = regimeService;
    this.buyableLow = buyableLow;
    this.buyableHigh = buyableHigh;
    this.onDeckFloor = onDeckFloor;
    this.funnelRsMin = funnelRsMin;
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
                  rs.getString("symbol"), close, rs.getBigDecimal("above_low_pct"),
                  rs.getString("setup_type"), pivot, rs.getString("footprint"), pct,
                  rs.getBigDecimal("breakout_pivot"), rs.getBigDecimal("vcp_pivot"),
                  rs.getBigDecimal("rs_rank"));
            },
            java.sql.Date.valueOf(screenDate), funnelRsMin);

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
   * A valid base with price in [pivot·buyableLow, pivot·buyableHigh] is immediately buyable (at the
   * breakout, not chased); in [pivot·onDeckFloor, pivot·buyableLow) it is on-deck (tightening toward
   * the pivot); everything else — no valid base, extended past the pivot, or well below it — is a watch.
   */
  private Bucket bucket(FunnelRow row) {
    if (row.pivot() == null || row.pivot().signum() == 0 || row.close() == null) {
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
