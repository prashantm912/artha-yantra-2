package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;

/**
 * Siva #2 Open=High / Open=Low primitive (master plan section 12, doc section 3.2). A pure detector of
 * the front-future "open == session extreme" mark plus the deterministic FNO-structure probability TIER
 * the doc's section-3.2 L489 list defines. Consumed by {@link OpenHighLowGate}.
 *
 * <p><b>OH / OL mark (front future, replay-safe):</b> OH is "the session OPEN is (still) the running
 * session HIGH", OL is "the session OPEN is the running session LOW", read off the index future's bars
 * from the session start through the deploy bar. Exact O==H/O==L is too brittle on real ticks, so a
 * fixed {@code 1}-point tolerance is allowed (the open within a point of the extreme counts) - the
 * smallest concession that keeps a near-perfect mark from being missed without admitting an ordinary
 * bar. Both checks read the same session window {@link EngineSeries#sessionStart(int)} bounds, mirroring
 * {@link GapState}/{@link MarketStructure}.
 *
 * <p><b>Probability tier (the honest equivalent of the unavailable OiPulse "&gt;=90% badge"):</b> the
 * doc's external OI-Pulse AI badge is a Phase-4 OiPulse-parity model we do NOT have, so it is treated as
 * an OPTIONAL, currently-unavailable confirmation - we never require it. What we CAN compute from our
 * own data is the doc-section-3.2 L489 "Probability tiers from FNO data" mapping, the deterministic
 * FNO-structure tier. v1 computes it from (front-future OH/OL) x (the underlying OI quadrant), since the
 * per-strike ATM+-3 OH/OL leg is unavailable without a new market-data endpoint (see
 * {@link OpenHighLowGate} and the strategy YAML). The mapping:
 *
 * <ul>
 *   <li><b>Future OH + bullish quadrant (LB/SC)</b> =&gt; {@link Tier#HIGH} for a CE (the L489 "OH on
 *       Futures + OH on Calls + OL on Puts = HIGH (bullish)" proxy - the future OH stands in for the
 *       per-strike confluence we cannot yet read, the quadrant supplies the directional confirmation).
 *   <li><b>Future OL + bearish quadrant (SB/LU)</b> =&gt; {@link Tier#HIGH} for a PE (the "Puts OH +
 *       Calls OL = HIGH (bearish)" proxy).
 *   <li><b>Future OH AND OL together</b> (the open is simultaneously the session high and low - a
 *       single-bar / flat session) =&gt; {@link Tier#STAND_ASIDE} (the L489 "Calls OH AND Puts OH
 *       together = MILD/sideways = stand aside" - ambiguous, two-sided, no clean directional intent).
 *   <li><b>A mark present but the quadrant does NOT confirm the side</b> (or is NEUTRAL) =&gt;
 *       {@link Tier#MILD} (the L489 "few strikes" / opposite-trend mild case - some structure, no
 *       confirmation).
 *   <li><b>No OH/OL mark on the requested side</b> =&gt; {@link Tier#STAND_ASIDE} (nothing to trade).
 * </ul>
 *
 * <p>Pure + deterministic over fixed closed bars + a fixed OI snapshot, so a replay recomputes it
 * byte-identically (section 12.9).
 */
public final class OpenHighLow {

  private OpenHighLow() {}

  /** The doc-section-3.2 L489 FNO-structure probability tier. */
  public enum Tier {
    /** The bullish/bearish HIGH-tier proxy is met for the side - the gate may proceed. */
    HIGH,
    /** Some structure but no quadrant confirmation (few-strikes / opposite-trend) - block. */
    MILD,
    /** Two-sided OH+OL, or no mark on the side - sideways/nothing to trade - block. */
    STAND_ASIDE
  }

  /** The front-future open-extreme marks for the session containing the deploy bar. */
  public record Marks(boolean openHigh, boolean openLow) {}

  /** section 3.2: the open is "at" the session extreme within this point tolerance. */
  private static final BigDecimal TICK_TOLERANCE = new BigDecimal("1");

  private static final Marks NONE = new Marks(false, false);

  /**
   * Detect the front-future OH/OL marks for {@code index}'s IST session.
   *
   * @param future the index-future 3-min series the scalper evaluates on
   * @param index the just-closed deploy bar index
   */
  public static Marks marks(EngineSeries future, int index) {
    if (future == null || index < 0 || index >= future.size()) {
      return NONE;
    }
    int sessionStart = future.sessionStart(index);
    BigDecimal open = future.candle(sessionStart).open();
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
    // OH: the session open is (still) the running session high (within tolerance) - high never ran
    // more than a tick above the open. OL: the open is the running session low (within tolerance).
    boolean openHigh = high.subtract(open).compareTo(TICK_TOLERANCE) <= 0;
    boolean openLow = open.subtract(low).compareTo(TICK_TOLERANCE) <= 0;
    return new Marks(openHigh, openLow);
  }

  /**
   * The doc-section-3.2 L489 probability tier for the requested side, from the front-future marks x the
   * underlying OI quadrant. See the class doc for the full mapping. A null {@code oi} or a NEUTRAL /
   * non-confirming quadrant can never yield {@link Tier#HIGH} (the gate degrades to a block).
   *
   * @param future the index-future 3-min series
   * @param index the just-closed deploy bar index
   * @param side CE (bullish: wants OH) or PE (bearish: wants OL)
   * @param oi the Tier-1 OI snapshot (its {@code underlying()} quadrant supplies the confirmation)
   */
  public static Tier tier(EngineSeries future, int index, OptionType side, Oi oi) {
    Marks marks = marks(future, index);
    // Two-sided OH+OL (e.g. a single-bar / flat session) is the L489 both-sides sideways stand-aside.
    if (marks.openHigh() && marks.openLow()) {
      return Tier.STAND_ASIDE;
    }
    boolean ce = side == OptionType.CE;
    boolean mark = ce ? marks.openHigh() : marks.openLow();
    if (!mark) {
      return Tier.STAND_ASIDE; // no OH (CE) / OL (PE) mark -> nothing to trade
    }
    boolean confirmed =
        oi != null
            && oi.underlying() != null
            && (ce ? oi.underlying().bullish() : oi.underlying().bearish());
    return confirmed ? Tier.HIGH : Tier.MILD;
  }
}
