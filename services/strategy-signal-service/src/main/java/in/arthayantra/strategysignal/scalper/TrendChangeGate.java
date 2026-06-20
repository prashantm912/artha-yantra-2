package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Siva #12 Trend-Change pre-gate (master plan section 12, doc section 3.12). Catches a SHIFT in direction
 * (sideways/down -&gt; up, or sideways/up -&gt; down) rather than a continuation breakout, so it is a
 * HARD pre-entry filter consulted in {@link ScalperConfluenceGate} for strategies that declare it. All
 * four legs are required; any missing leg BLOCKS (a trend change is high-conviction-only - never a
 * false fire):
 *
 * <ol>
 *   <li><b>Price market-structure break</b> in the side's direction - a swing-high taken out to the
 *       upside (CE) or a swing-low taken out to the downside (PE), via {@link MarketStructure}.
 *   <li><b>Trending-OI momentum shift</b> in the side's direction with a &gt;=50% quantified shift -
 *       CE wants put-OI RISING and call-OI FALLING ({@code peOiDelta>0 && ceOiDelta<0}) with the
 *       Tier-1 call-put delta imbalance % at/above 50; PE is the mirror. Null signed deltas or a null
 *       imbalance DEGRADE to a block (a missing derivation can never confirm a reversal).
 *   <li><b>2-candle 3rd-candle confirmation</b> (section 3.1) via {@link TwoCandleGate} - the two bars before
 *       the deploy bar must be the side's colour, above the volume floor, with a strong 2nd body.
 *   <li><b>Late-reversal cap</b> - per the deck, avoid a fresh DOWN-reversal after ~14:30 (after which
 *       both OIs falling = participants squaring off, the action is over). The cap is bearish-only.
 * </ol>
 *
 * <p><b>Structural stop:</b> the broken swing pivot is the anchor (the swing-high for a long, the
 * swing-low for a short). section 3.12 gives no numeric SL and permits a ~10-20 pt benefit-of-doubt
 * allowance ONLY when the OI convincingly confirms; v1 anchors the SL on the raw swing pivot (the
 * documented, bar-derivable anchor) and leaves the allowance to live SL-management - so the persisted
 * stop is pure/deterministic and the engine never sizes off a discretionary pad.
 *
 * <p>Pure + deterministic over fixed closed bars + a fixed OI snapshot, so a replay recomputes it
 * byte-identically (section 12.9).
 */
public final class TrendChangeGate {

  private TrendChangeGate() {}

  /** Whether the trend-change entry may proceed and, if so, the broken-swing-pivot structural stop. */
  public record Verdict(boolean pass, BigDecimal stopLevel) {}

  private static final Verdict BLOCK = new Verdict(false, null);
  /** section 3.12: the deck's quantified >=50% Trending-OI shift floor. */
  private static final BigDecimal MIN_SHIFT_PCT = new BigDecimal("50");
  /** section 3.12: avoid a fresh DOWN-reversal after ~14:30. */
  private static final LocalTime DOWN_REVERSAL_CAP = LocalTime.of(14, 30);

  /**
   * Evaluate the trend-change pre-gate at the just-closed deploy bar.
   *
   * @param future the index-future 3-min series the scalper evaluates on
   * @param index the just-closed deploy bar index
   * @param side CE (up-reversal) or PE (down-reversal)
   * @param underlying the underlying name (drives the §3.1 volume floor)
   * @param oi the Tier-1 OI snapshot (signed CE/PE deltas + the call-put imbalance %)
   * @param istTime the bar's IST wall-clock (the ~14:30 down-reversal cap)
   */
  public static Verdict evaluate(
      EngineSeries future,
      int index,
      OptionType side,
      String underlying,
      Oi oi,
      LocalTime istTime) {
    if (oi == null) {
      return BLOCK; // no OI snapshot -> cannot confirm a momentum shift -> never fire
    }
    boolean ce = side == OptionType.CE;
    // (d) late-reversal cap: a fresh DOWN-reversal after ~14:30 is the day's tail -> avoid.
    if (!ce && istTime != null && !istTime.isBefore(DOWN_REVERSAL_CAP)) {
      return BLOCK;
    }
    // (b) Trending-OI momentum shift in the side's direction, >=50% quantified. Null deltas/imbalance
    // degrade to a block (a missing derivation can never confirm the reversal).
    if (!oiShift(oi, ce)) {
      return BLOCK;
    }
    // (c) 2-candle 3rd-candle confirmation (§3.1) - the same hard multi-bar formation #1 uses.
    if (!TwoCandleGate.detect(future, index, side, underlying).present()) {
      return BLOCK;
    }
    // (a) price market-structure break in the side's direction; the broken pivot anchors the SL.
    MarketStructure.Break structure = MarketStructure.detect(future, index, side);
    if (!structure.broke()) {
      return BLOCK;
    }
    return new Verdict(true, structure.pivot());
  }

  /**
   * The Tier-1 OI momentum shift: CE wants put-OI rising AND call-OI falling; PE the mirror; both with
   * the call-put delta imbalance % at/above the {@code MIN_SHIFT_PCT} floor. Null signed deltas or a
   * null imbalance return {@code false} (block) - the reversal cannot be confirmed without them.
   */
  private static boolean oiShift(Oi oi, boolean ce) {
    BigDecimal ceDelta = oi.ceOiDelta();
    BigDecimal peDelta = oi.peOiDelta();
    BigDecimal imbalance = oi.callPutDeltaImbalancePct();
    if (ceDelta == null || peDelta == null || imbalance == null) {
      return false;
    }
    boolean directional =
        ce
            ? peDelta.signum() > 0 && ceDelta.signum() < 0
            : ceDelta.signum() > 0 && peDelta.signum() < 0;
    return directional && imbalance.compareTo(MIN_SHIFT_PCT) >= 0;
  }
}
