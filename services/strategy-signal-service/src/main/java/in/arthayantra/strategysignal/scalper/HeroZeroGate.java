package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Siva #7 Hero-Zero pre-gate (doc section 7). An EXPIRY-DAY-ONLY, BUY-SIDE-ONLY (long premium, defined
 * risk - never short) end-of-day scalp consulted in {@link ScalperConfluenceGate} for strategies that
 * declare the {@code hero-zero} tag. After ~14:30 the highest-OI build-up has marked the day's range
 * (the Put strike is support, the Call strike resistance); the trade fires on a REAL break of that
 * range confirmed by a short-covering collapse on one side, then BUYS the cheap directional option for
 * the closing drift into expiry.
 *
 * <p>All gating legs are required; any failing leg BLOCKS (a hero-zero buy is high-conviction-only -
 * never a false fire). Because the move-confirmation REQUIRES the OI derivations, a null/inert OI
 * snapshot (the monthly-expiry suppression, or any unavailable read) DEGRADES to a block - the gate
 * can never confirm a break without OI:
 *
 * <ol>
 *   <li><b>Expiry day only.</b> Off an index weekly-expiry day the gate is inert and blocks. On a
 *       MONTHLY expiry day the prior-month chain-OI is corrupted (the deck's "ignore OI on monthly
 *       expiry" caveat - the same S24 suppression {@link MarketOiClient} applies), so the gate also
 *       blocks (no trustworthy OI to confirm the break).
 *   <li><b>After ~14:30, before the 15:20 fresh-entry cap.</b> The range is only marked after 14:30;
 *       the 15:20 cap leaves head-room before the 15:20 square-off / 15:20-3:20 hard close (no fresh
 *       buy in the last minutes).
 *   <li><b>A REAL move - both OI AND price moved &gt;50% on the SAME side.</b> The call/put dOI
 *       imbalance % is at/above 50 (the OI leg) AND the spurt price-move magnitude is at/above 50% in
 *       the side's direction (the price leg). OI rising without price following (or the mirror) is the
 *       deck's "skip - not a real move".
 *   <li><b>Short-covering on the side.</b> Bullish (CE) needs a drastic fall in CALL OI (call writers
 *       covering: {@code ceOiDelta < 0}) with the side's quadrant reading SHORT_COVERING; bearish (PE)
 *       the mirror (put writers covering: {@code peOiDelta < 0}). A both-sides long-unwinding read (both
 *       quadrants LONG_UNWINDING) is the deck's explicit skip and blocks.
 *   <li><b>Closing toward the day extreme.</b> Bullish fires only when the deploy bar is closing toward
 *       the session HIGH; bearish toward the session LOW. The side handed in by the seam (VWAP-routed)
 *       must match this price location, else block.
 *   <li><b>RSI not overbought / not oversold.</b> A bullish buy is skipped when RSI is already
 *       overbought (&gt;= the cap); bearish when oversold (&lt;= the floor). A null RSI blocks.
 * </ol>
 *
 * <p><b>One strike from the SC strike (documented, live-managed):</b> the deck buys the option ONE
 * strike BELOW the short-covering CALL strike (bullish) / ONE strike ABOVE the SC PUT strike (bearish)
 * - never the ultra-cheap already-covered strike. The per-strike SC strike is a LIVE-only derivation
 * ({@link MarketOiClient} is never in deterministic replay), so v1 leaves the concrete leg to the
 * shared {@link StrikePicker} delta/premium band (the slightly-ITM 0.6-0.7 strike one step inside the
 * SC strike) and records the "one strike away from the SC strike" rule in the strategy YAML rather than
 * resolving a per-strike SC strike on the parity path.
 *
 * <p><b>Structural stop:</b> the OPPOSITE session extreme - a bullish CE fired toward the day HIGH is
 * invalidated by a fall back to the session LOW; a bearish PE toward the day LOW by a rally back to the
 * session HIGH. (The premium-based SL = 50% of premium is the primary live stop, carried by the YAML
 * {@code premium_pct} rule + the 15:20 time stop; this index-future anchor is the deterministic,
 * replay-safe structural reference.)
 *
 * <p>Pure + deterministic over fixed closed bars + a fixed OI snapshot, so a replay recomputes it
 * byte-identically (section 12.9).
 */
public final class HeroZeroGate {

  private HeroZeroGate() {}

  /** Whether the hero-zero buy may proceed and, if so, the opposite-extreme structural stop. */
  public record Verdict(boolean pass, BigDecimal stopLevel) {}

  private static final Verdict BLOCK = new Verdict(false, null);
  /** section 7: the &gt;50% OI / price "real move" floor (both legs, same side). */
  private static final BigDecimal MIN_MOVE_PCT = new BigDecimal("50");
  /** section 7: the range is only marked after ~14:30. */
  static final LocalTime RANGE_FROM = LocalTime.of(14, 30);
  /** section 7: no fresh buy in the last minutes (head-room before the 15:20 square-off / hard close). */
  static final LocalTime FRESH_ENTRY_CAP = LocalTime.of(15, 20);
  /** RSI exhaustion caps - a bullish buy avoids an already-overbought tape, bearish an oversold one. */
  private static final BigDecimal RSI_OVERBOUGHT = new BigDecimal("80");
  private static final BigDecimal RSI_OVERSOLD = new BigDecimal("20");
  /** "closing toward" the extreme: within this fraction of the session range of the matching extreme. */
  private static final BigDecimal NEAR_EXTREME_FRACTION = new BigDecimal("0.25");

  /**
   * Evaluate the Hero-Zero pre-gate at the just-closed deploy bar.
   *
   * @param future the index-future 3-min series the scalper evaluates on
   * @param index the just-closed deploy bar index
   * @param side CE (bullish, toward the day high) or PE (bearish, toward the day low) - the seam's
   *     VWAP-routed side, verified here against the closing-toward-the-extreme price location
   * @param oi the Tier-1 OI snapshot (signed CE/PE deltas, imbalance %, spurt price magnitude,
   *     quadrants); a null snapshot DEGRADES to a block
   * @param rsi the deploy bar's RSI(14) (the overbought/oversold rail); null blocks
   * @param istTime the bar's IST wall-clock (the 14:30 range floor + the 15:20 fresh-entry cap)
   * @param expiryDay whether {@code istTime}'s date is an index weekly-expiry day (off-expiry blocks)
   * @param monthlyExpiryDay whether it is a MONTHLY expiry day (prior-month OI corrupt - blocks)
   */
  public static Verdict evaluate(
      EngineSeries future,
      int index,
      OptionType side,
      Oi oi,
      BigDecimal rsi,
      LocalTime istTime,
      boolean expiryDay,
      boolean monthlyExpiryDay) {
    // (1) expiry day only; a monthly expiry's prior-month OI is corrupt -> cannot confirm a break.
    if (!expiryDay || monthlyExpiryDay) {
      return BLOCK;
    }
    // (2) after ~14:30 (the range is marked), before the 15:20 fresh-entry cap.
    if (istTime == null || istTime.isBefore(RANGE_FROM) || !istTime.isBefore(FRESH_ENTRY_CAP)) {
      return BLOCK;
    }
    // The move-confirmation needs OI; a null/inert snapshot can never confirm -> never fire.
    if (oi == null) {
      return BLOCK;
    }
    boolean ce = side == OptionType.CE;
    // (6) RSI not overbought (CE) / not oversold (PE); a null RSI blocks (the data is required).
    if (rsi == null
        || (ce ? rsi.compareTo(RSI_OVERBOUGHT) >= 0 : rsi.compareTo(RSI_OVERSOLD) <= 0)) {
      return BLOCK;
    }
    // (4) both-sides long-unwinding is the deck's explicit skip.
    if (oi.underlying() == OiQuadrant.LONG_UNWINDING && oi.futures() == OiQuadrant.LONG_UNWINDING) {
      return BLOCK;
    }
    // (3) a REAL move - both OI AND price moved >50% on the SAME side.
    if (!realMove(oi, ce)) {
      return BLOCK;
    }
    // (4) short-covering on the side: a drastic fall in the side's OI (call writers covering for CE /
    // put writers covering for PE) with a SHORT_COVERING quadrant read.
    if (!shortCovering(oi, ce)) {
      return BLOCK;
    }
    // (5) closing toward the matching session extreme (high for CE, low for PE); the opposite extreme
    // is the structural stop.
    BigDecimal stop = towardExtremeStop(future, index, ce);
    if (stop == null) {
      return BLOCK;
    }
    return new Verdict(true, stop);
  }

  /**
   * The deck's "real move": both the OI and the price legs at/above the &gt;50% floor on the SIDE's
   * direction. The OI leg is the call/put dOI imbalance % (at/above 50); the price leg is the spurt
   * price magnitude (at/above 50%) with its SIGN matching the side (CE wants price up, PE down). Null
   * imbalance or null spurt-price returns {@code false} - OI rising without a confirmed price follow
   * (or a missing derivation) is never a real move.
   */
  private static boolean realMove(Oi oi, boolean ce) {
    BigDecimal imbalance = oi.callPutDeltaImbalancePct();
    BigDecimal pricePct = oi.spurtPricePct();
    if (imbalance == null || pricePct == null) {
      return false;
    }
    boolean oiLeg = imbalance.compareTo(MIN_MOVE_PCT) >= 0;
    boolean priceLeg =
        pricePct.abs().compareTo(MIN_MOVE_PCT) >= 0 && (ce ? pricePct.signum() > 0 : pricePct.signum() < 0);
    return oiLeg && priceLeg;
  }

  /**
   * Short-covering on the side: bullish (CE) wants a drastic FALL in CALL OI ({@code ceOiDelta < 0} -
   * call writers covering) with the side's quadrant reading SHORT_COVERING; bearish (PE) the mirror
   * ({@code peOiDelta < 0}). Either the underlying OR the futures quadrant reading SHORT_COVERING
   * satisfies the quadrant leg. Null signed deltas return {@code false} (no covering can be confirmed).
   */
  private static boolean shortCovering(Oi oi, boolean ce) {
    BigDecimal sideDelta = ce ? oi.ceOiDelta() : oi.peOiDelta();
    if (sideDelta == null || sideDelta.signum() >= 0) {
      return false; // the side's OI must be FALLING (writers covering), not flat/rising
    }
    return oi.underlying() == OiQuadrant.SHORT_COVERING || oi.futures() == OiQuadrant.SHORT_COVERING;
  }

  /**
   * The toward-extreme check + the opposite-extreme structural stop: the deploy bar must be closing
   * within {@link #NEAR_EXTREME_FRACTION} of the session range of the matching extreme (the HIGH for
   * CE, the LOW for PE). Returns the OPPOSITE session extreme (the LOW for CE, the HIGH for PE) as the
   * structural stop, or {@code null} when the bar is not closing toward the extreme (block) or the
   * series is unusable.
   */
  private static BigDecimal towardExtremeStop(EngineSeries future, int index, boolean ce) {
    if (future == null || index < 0 || index >= future.size()) {
      return null;
    }
    int sessionStart = future.sessionStart(index);
    BigDecimal high = future.candle(sessionStart).high();
    BigDecimal low = future.candle(sessionStart).low();
    for (int i = sessionStart + 1; i <= index; i++) {
      BigDecimal h = future.candle(i).high();
      BigDecimal l = future.candle(i).low();
      if (h.compareTo(high) > 0) {
        high = h;
      }
      if (l.compareTo(low) < 0) {
        low = l;
      }
    }
    BigDecimal range = high.subtract(low);
    if (range.signum() <= 0) {
      return null; // a flat session has no range to be "closing toward"
    }
    BigDecimal close = future.candle(index).close();
    BigDecimal band = range.multiply(NEAR_EXTREME_FRACTION);
    if (ce) {
      // closing toward the HIGH: within the top band of the range; stop = the session LOW.
      return high.subtract(close).compareTo(band) <= 0 ? low : null;
    }
    // closing toward the LOW: within the bottom band of the range; stop = the session HIGH.
    return close.subtract(low).compareTo(band) <= 0 ? high : null;
  }
}
