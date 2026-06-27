package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategysignal.scalper.MarketOiClient.OpenHighStats;
import in.arthayantra.strategysignal.scalper.MarketOiClient.StrikeStat;
import java.math.BigDecimal;

/**
 * Siva #2 Open=High / Open=Low primitive (master plan section 12, doc section 3.2). A pure detector of
 * the front-future "open == session extreme" mark plus the SOURCE-FAITHFUL FNO-structure probability
 * TIER (the Day-14 "Open &amp; High" deck Table-1 + Table-2). Consumed by {@link OpenHighLowGate}.
 *
 * <p><b>OH / OL mark (front future, replay-safe):</b> OH is "the session OPEN is (still) the running
 * session HIGH", OL is "the session OPEN is the running session LOW", read off the index future's bars
 * from the session start through the deploy bar. Exact O==H/O==L is too brittle on real ticks, so a
 * fixed {@code 1}-point tolerance is allowed - the smallest concession that keeps a near-perfect mark
 * from being missed. This is the source's "Futures OH" input.
 *
 * <p><b>Probability tier (source-faithful, replacing the v1 OI-quadrant proxy):</b> the tier is now
 * graded from the front-future OH/OL mark PLUS the per-strike option-premium footprint
 * ({@link OpenHighStats}, the {@code /options/strike-session-stats} endpoint), exactly as the deck
 * defines it - NOT from the underlying OI quadrant (that proxy is DROPPED).
 *
 * <p><b>Table-1 (FNO structure, per-strike footprint over ATM+-window):</b>
 * <ul>
 *   <li>Futures OH AND {@code ceOhCount >= minStrikes} AND {@code peOlCount >= 1} =&gt;
 *       {@link Tier#HIGH} (bullish, side=CE). Mirror: Futures OL AND {@code peOhCount >= minStrikes}
 *       AND {@code ceOlCount >= 1} =&gt; {@link Tier#HIGH} (bearish, side=PE).
 *   <li>A footprint present but {@code 1 <= ceOhCount < minStrikes} (few strikes), or Futures-OH
 *       without the strike footprint =&gt; {@link Tier#MILD}.
 *   <li>Both-sides (CE OH footprint AND PE OH footprint together: {@code ceOhCount>=1 AND
 *       peOhCount>=1}), OR no relevant mark on the side =&gt; {@link Tier#STAND_ASIDE}.
 * </ul>
 *
 * <p><b>Table-2 (price/volume modifier, on the identified OH side):</b> if the OH strikes' premium
 * FELL (the representative OH strike's {@code last < open} or {@code last < high}) AND its
 * {@code declineVolume >= fallVolumeFloor} =&gt; downgrade to {@link Tier#LOW} (fell on heavy volume =
 * a real opposite player). Fell on {@code < floor} or flat =&gt; keep the Table-1 tier.
 *
 * <p><b>Extra LOW rule:</b> if {@code |fallPctFromPrevClose| > maxPrevCloseFallPct} on the
 * identified-side representative OH strike =&gt; {@link Tier#LOW} (a &gt;50% fall = a bigger player
 * took the opposite view).
 *
 * <p>Degrade-to-safe: null/empty stats or no front-future mark can NEVER yield {@link Tier#HIGH} (the
 * gate then blocks). Pure + deterministic, so a replay recomputes it byte-identically (section 12.9).
 */
public final class OpenHighLow {

  private OpenHighLow() {}

  /** The Table-1/Table-2 FNO-structure probability tier. */
  public enum Tier {
    /** The HIGH-tier footprint is met for the side - the gate may proceed. */
    HIGH,
    /** Few strikes / a future-only mark - some structure, not confirmed - block. */
    MILD,
    /** The price/volume modifier or the &gt;50% prev-close fall flagged an opposite player - block. */
    LOW,
    /** Two-sided OH footprint, or no mark on the side - sideways/nothing to trade - block. */
    STAND_ASIDE,
    /**
     * The deck p20 AVOID veto - a bigger player took the opposite side (premium fell &gt;50% AND/OR
     * the identified strike's change-in-OI rose &gt;50%). Produced only by the W3 PR-6 veto; here it
     * maps to a 0% probability (a hard no-trade), distinct from the sideways {@link #STAND_ASIDE}.
     */
    AVOID
  }

  /**
   * W3 PR-5 (S24 "OIP-AI probability", owner tier-&gt;%): our own deterministic Open=High success
   * probability from the Day-14 Table-1/Table-2 read, replacing oipulse's proprietary AI percentage.
   * HIGH 90, MILD 60, LOW 30, STAND_ASIDE/AVOID 0 (a no-trade). See
   * {@code docs/superpowers/plans/2026-06-27-oip-ai-probability-spec.md}.
   */
  public static int probabilityPct(Tier tier) {
    return switch (tier) {
      case HIGH -> 90;
      case MILD -> 60;
      case LOW -> 30;
      case STAND_ASIDE, AVOID -> 0;
    };
  }

  /** The strong / "badge" read (oipulse's &gt;90%): true only for the HIGH (90%) tier. */
  public static boolean badge(Tier tier) {
    return tier == Tier.HIGH;
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
    // OH: the session open is (still) the running session high (within tolerance). OL: the mirror.
    boolean openHigh = high.subtract(open).compareTo(TICK_TOLERANCE) <= 0;
    boolean openLow = open.subtract(low).compareTo(TICK_TOLERANCE) <= 0;
    return new Marks(openHigh, openLow);
  }

  /**
   * The source-faithful Table-1/Table-2 probability tier for the requested side, from the front-future
   * marks x the per-strike option-premium footprint. See the class doc for the full mapping. Null/empty
   * stats or no front-future mark on the side can never yield {@link Tier#HIGH}.
   *
   * @param futureMarks the front-future OH/OL marks ({@link #marks(EngineSeries, int)})
   * @param stats the ATM+-window per-strike OH/OL footprint (null/empty degrades to a block)
   * @param side CE (bullish: wants Futures-OH + CE-OH footprint) or PE (bearish: mirror)
   * @param props the #2 thresholds (min-strikes / fall-volume floor / max prev-close fall %)
   */
  public static Tier tier(
      Marks futureMarks, OpenHighStats stats, OptionType side, ScalperOiProps props) {
    if (futureMarks == null || stats == null || stats.items() == null || stats.items().isEmpty()) {
      return Tier.STAND_ASIDE; // no front-future marks or no footprint -> nothing to grade -> block
    }
    boolean ce = side == OptionType.CE;
    boolean futureMark = ce ? futureMarks.openHigh() : futureMarks.openLow();
    if (!futureMark) {
      return Tier.STAND_ASIDE; // no Futures-OH (CE) / Futures-OL (PE) -> nothing to trade
    }

    // Count the per-strike OH/OL footprint by leg side.
    int ceOhCount = 0;
    int peOhCount = 0;
    int ceOlCount = 0;
    int peOlCount = 0;
    for (StrikeStat s : stats.items()) {
      boolean isCe = s.type() == OptionType.CE;
      if (s.ohMark()) {
        if (isCe) {
          ceOhCount++;
        } else {
          peOhCount++;
        }
      }
      if (s.olMark()) {
        if (isCe) {
          ceOlCount++;
        } else {
          peOlCount++;
        }
      }
    }

    // Both-sides OH footprint (CE OH AND PE OH together) is the deck's sideways stand-aside.
    if (ceOhCount >= 1 && peOhCount >= 1) {
      return Tier.STAND_ASIDE;
    }

    int minStrikes = props.openHighMinStrikes().intValue();
    // Table-1: the side's OH-footprint count + the opposite leg's OL confirmation.
    int sideOhCount = ce ? ceOhCount : peOhCount;
    int oppositeOlCount = ce ? peOlCount : ceOlCount;
    if (sideOhCount < 1) {
      // a Futures-OH with NO strike footprint on the side -> MILD (future-only mark, not confirmed).
      return Tier.MILD;
    }
    Tier table1 =
        (sideOhCount >= minStrikes && oppositeOlCount >= 1) ? Tier.HIGH : Tier.MILD;

    // Table-2 + the >50% prev-close-fall LOW rule apply only when Table-1 reached HIGH.
    if (table1 != Tier.HIGH) {
      return table1;
    }
    StrikeStat representative = representativeOh(stats, side);
    if (representative == null) {
      return Tier.HIGH; // no representative OH strike to modify with -> keep Table-1
    }
    if (fellOnHeavyVolume(representative, props) || exceedsPrevCloseFall(representative, props)) {
      return Tier.LOW;
    }
    return Tier.HIGH;
  }

  /**
   * The representative OH strike on {@code side}: the one nearest the ATM ({@code stats.atmStrike()})
   * among the side's OH-marked legs, or the first such leg when no ATM is resolvable. Null when the
   * side carries no OH-marked strike (cannot happen when Table-1 reached HIGH, but kept defensive).
   */
  private static StrikeStat representativeOh(OpenHighStats stats, OptionType side) {
    StrikeStat best = null;
    BigDecimal bestDiff = null;
    for (StrikeStat s : stats.items()) {
      if (s.type() != side || !s.ohMark()) {
        continue;
      }
      if (stats.atmStrike() == null) {
        return s; // no ATM -> the first OH leg is the representative
      }
      BigDecimal diff = s.strike().subtract(stats.atmStrike()).abs();
      if (bestDiff == null || diff.compareTo(bestDiff) < 0) {
        bestDiff = diff;
        best = s;
      }
    }
    return best;
  }

  /**
   * Table-2: the OH strike's premium FELL ({@code last < open} OR {@code last < high}) AND its
   * {@code declineVolume >= fallVolumeFloor}. A null {@code last}/{@code declineVolume} is unavailable
   * = not a downgrade (degrade-to-safe: an absent modifier never weakens an otherwise-HIGH footprint).
   */
  private static boolean fellOnHeavyVolume(StrikeStat s, ScalperOiProps props) {
    BigDecimal last = s.last();
    if (last == null || s.declineVolume() == null) {
      return false;
    }
    boolean fell =
        (s.open() != null && last.compareTo(s.open()) < 0)
            || (s.high() != null && last.compareTo(s.high()) < 0);
    if (!fell) {
      return false;
    }
    return BigDecimal.valueOf(s.declineVolume()).compareTo(props.openHighFallVolumeFloor()) >= 0;
  }

  /**
   * The extra-LOW rule: {@code |fallPctFromPrevClose| > maxPrevCloseFallPct} on the OH strike (a
   * &gt;50% fall = a bigger player took the opposite view). A null value does not trigger it.
   */
  private static boolean exceedsPrevCloseFall(StrikeStat s, ScalperOiProps props) {
    BigDecimal fall = s.fallPctFromPrevClose();
    return fall != null && fall.abs().compareTo(props.openHighMaxPrevCloseFallPct()) > 0;
  }
}
