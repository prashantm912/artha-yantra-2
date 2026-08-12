package in.arthayantra.marketdata.futures.preopen;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Pure row-math + advance/decline split for the Futures Pre-Open Market scan (oipulse
 * §futures/pre-open-market). Given a symbol's pre-open indicative price, its net change vs the previous
 * close, and the previous session's high/low, it derives the page's per-row fields and the
 * advances/declines/unchanged counts — exactly the client-side fold oipulse does over its raw
 * {@code inPreOpenClose / inPreOpenChange / inPrevDayBreak} rows. No I/O, no Spring — the {@link
 * FuturesPreOpenService} feeds it the resolved quotes; this class is what the unit test pins.
 *
 * <ul>
 *   <li>{@code prevClose} = {@code preOpenPrice − netChange} (the same derivation the index pre-open
 *       uses; oipulse computes {@code inPreOpenClose − inPreOpenChange}).
 *   <li>{@code changePct} = {@code netChange / prevClose × 100} (2 dp), {@code null} when either is
 *       absent or the prev close is zero.
 *   <li>{@code prevDayBreak} = {@code "H"} when the pre-open price is above the previous day's high,
 *       {@code "L"} when below the previous day's low, else {@code null} — the High/Low Break badge.
 * </ul>
 */
public final class FuturesPreOpenScan {

  private FuturesPreOpenScan() {}

  /** One scanned symbol — the shape both the stock and index tables render. */
  public record PreOpenRow(
      String symbol,
      @Schema(type = "string", types = {"string", "null"})
      BigDecimal preOpenPrice,
      @Schema(type = "string", types = {"string", "null"})
      BigDecimal prevClose,
      @Schema(type = "string", types = {"string", "null"})
      BigDecimal change,
      @Schema(type = "string", types = {"string", "null"})
      BigDecimal changePct,
      @Schema(types = {"string", "null"})
      String prevDayBreak) {}

  /**
   * The whole scan the controller serves: the NSE session phase + the stock and index rows + the
   * headline (market = stock) advance/decline/unchanged counts.
   */
  public record FuturesPreOpen(
      String phase,
      boolean preOpen,
      OffsetDateTime asOf,
      int advances,
      int declines,
      int unchanged,
      List<PreOpenRow> stocks,
      List<PreOpenRow> indices) {}

  /**
   * Builds a row from a symbol's pre-open price + net change + (nullable) previous-day high/low. A
   * price-less symbol (quote did not resolve) still lists, with null derived fields.
   */
  public static PreOpenRow row(
      String symbol,
      BigDecimal preOpenPrice,
      BigDecimal netChange,
      BigDecimal prevHigh,
      BigDecimal prevLow) {
    BigDecimal prevClose =
        preOpenPrice != null && netChange != null ? preOpenPrice.subtract(netChange) : null;
    return new PreOpenRow(
        symbol,
        preOpenPrice,
        prevClose,
        netChange,
        changePct(netChange, prevClose),
        classifyBreak(preOpenPrice, prevHigh, prevLow));
  }

  /** {@code net_change / prevClose × 100} (2 dp), or {@code null} when either is absent / prevClose=0. */
  public static BigDecimal changePct(BigDecimal netChange, BigDecimal prevClose) {
    if (netChange == null || prevClose == null || prevClose.signum() == 0) {
      return null;
    }
    return netChange.multiply(BigDecimal.valueOf(100)).divide(prevClose, 2, RoundingMode.HALF_UP);
  }

  /**
   * {@code "H"} when the pre-open price is strictly above the previous day's high, {@code "L"} when
   * strictly below the previous day's low, else {@code null}. Null when the price or the relevant
   * bound is missing.
   */
  public static String classifyBreak(BigDecimal preOpenPrice, BigDecimal prevHigh, BigDecimal prevLow) {
    if (preOpenPrice == null) {
      return null;
    }
    if (prevHigh != null && preOpenPrice.compareTo(prevHigh) > 0) {
      return "H";
    }
    if (prevLow != null && preOpenPrice.compareTo(prevLow) < 0) {
      return "L";
    }
    return null;
  }

  /**
   * Advance/decline/unchanged split by the sign of {@code change} — a positive change advances, a
   * negative one declines, and a zero OR price-less (null) change is unchanged (still listed, no sign).
   * Returned as {@code [advances, declines, unchanged]}.
   */
  public static int[] counts(List<PreOpenRow> rows) {
    int adv = 0;
    int dec = 0;
    int unch = 0;
    for (PreOpenRow r : rows) {
      int sign = r.change() == null ? 0 : r.change().signum();
      if (sign > 0) {
        adv++;
      } else if (sign < 0) {
        dec++;
      } else {
        unch++;
      }
    }
    return new int[] {adv, dec, unch};
  }
}
