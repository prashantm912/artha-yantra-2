package in.arthayantra.strategysignal.scalper;

import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Chart;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The Connect-the-Dots master confluence scorer (master plan §12.3, Siva strategy #10) — the
 * architectural heart the index-option core composes from. Scores, for a given side, the 5 chart dots
 * plus the OI/macro dots over a {@link ScalperGateContext} into a single confluence aggregate, reusing
 * {@link ScalperGates} so each rule is single-sourced.
 *
 * <p>Pure + deterministic — no clock, no network; the engine's chart {@code EntryEvaluator} runs first
 * and this confirms (the typed scorer that runs alongside the chart-only YAML grammar). The result
 * rides the signal side-channel, never the frozen {@code ScoreBreakdown} (§12.9).
 *
 * <p><b>VWAP is decisive [S22/S24]</b>: it carries the highest weight AND is a hard gate — a signal is
 * never bullish/bearish when price is on the wrong side of VWAP, regardless of the aggregate. The
 * 60-minute bias must also agree with the 3-minute side. Weights here are the v1 defaults; threshold
 * (and later the weights) are intended to move to DB params, not the Java.
 */
public final class ConnectTheDotsScorer {

  private ConnectTheDotsScorer() {}

  // v1 dot weights — VWAP decisive, futures-OI quadrant next, IV-rank a soft booster.
  private static final double W_VWAP = 2.5;
  private static final double W_OI = 1.5;
  private static final double W_IV = 0.8;
  private static final double W = 1.0;
  private static final BigDecimal IV_RANK_LOW = new BigDecimal("50");

  /**
   * One confluence dot's contribution. {@code absent} (signal-analysis rollup §Proposals P3) marks a
   * dot whose INPUT data is MISSING: it is WITHHELD from BOTH the numerator and the denominator (it
   * neither supports nor opposes), so a data gap is never scored as evidence against the side. The
   * recorded side-channel keeps serializing {@code dot}/{@code weight}/{@code supports} only, so the
   * JSON shape is unchanged — the exclusion shows up only in the (correspondingly higher) aggregate.
   */
  public record DotScore(String dot, double weight, boolean supports, String reason, boolean absent) {

    /** Present-dot form: {@code absent} defaults to false (keeps every existing call site intact). */
    public DotScore(String dot, double weight, boolean supports, String reason) {
      this(dot, weight, supports, reason, false);
    }
  }

  /**
   * The aggregate confluence verdict for a side. {@code standAside} is the T2.8 40/40 both-IV-high
   * suppression: when set, the confluence is forced invalid regardless of the aggregate (the
   * iv-pair dot also withholds support), so a high-IV/low-edge chop never fires.
   */
  public record Confluence(
      BigDecimal aggregate, OptionType side, boolean bullish, boolean bearish,
      boolean vwapAligned, boolean biasAligned, boolean standAside, List<DotScore> dots) {}

  /**
   * Score the confluence for {@code side}.
   *
   * @param ctx the per-bar snapshot
   * @param side CE on a long bias, PE on a short bias
   * @param bias60mDir the 60-minute bias direction: +1 bull, -1 bear, 0 unknown (never blocks)
   * @param threshold the aggregate a valid signal must reach (0..1)
   * @param props the Tier-1 OI-analytics thresholds (drastic / spurt / iv-pair); see {@link
   *     ScalperOiProps}
   * @param vwapHardGate whether price-vs-VWAP is a HARD validity gate (the default for every strategy).
   *     The #9 Morning Trade opening-tick path passes {@code false} before 10:30 IST — VWAP is "not yet
   *     actionable" so early, so it DEGRADES to a soft dot (still scored in the aggregate, never gates).
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate) {
    return score(ctx, side, bias60mDir, threshold, props, vwapHardGate, false);
  }

  /**
   * As {@link #score(ScalperGateContext, OptionType, int, BigDecimal, ScalperOiProps, boolean)} but with
   * the E4 {@code iv-per-strike} IV-fidelity reads opted in. When {@code ivPerStrikeGate} is true the dot
   * list gains two SOFT IV dots ({@code iv_slope} per-strike IV direction, {@code iv_abs_band} the 10-12
   * trend-play band) and the {@code standAside} suppression extends to a unilateral buy-side IV>40 cap.
   * When false the dot list and {@code standAside} are byte-identical to the 6-arg form (parity-safe).
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate) {
    return score(ctx, side, bias60mDir, threshold, props, vwapHardGate, ivPerStrikeGate, false);
  }

  /**
   * As the 7-arg form but with the E7 {@code premium-skew} dot opted in (§3.7/§6.7 Hero-Zero "favor the
   * side whose premium is lower; sit on the discount side as the buyer", Day 10). When {@code
   * premiumSkewDot} is true a SOFT warning dot ({@code premium_skew}) is appended that withholds support
   * — lowering the aggregate — only when the traded side is the richer (higher-premium) side AND no
   * {@code trending_cross}/{@code oi_spurt} cue corroborates. When false the dot list + aggregate are
   * byte-identical to the 7-arg form (conditional-add ⇒ the unarmed denominator never moves).
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate, boolean premiumSkewDot) {
    return score(
        ctx, side, bias60mDir, threshold, props, vwapHardGate, ivPerStrikeGate, premiumSkewDot, false);
  }

  /**
   * As the 8-arg form but with the E3 {@code dow-confluence} dot opted in (the Dow global cue: CE
   * confirmed when Dow is up, PE when down; an unknown direction is neutral). When false the dot list +
   * aggregate are byte-identical to the 8-arg form (conditional-add ⇒ the unarmed denominator never moves).
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate, boolean premiumSkewDot,
      boolean dowDot) {
    Chart c = ctx.chart();
    Oi oi = ctx.oi();
    Macro m = ctx.macro();
    boolean ce = side == OptionType.CE;

    boolean vwapSide = ce ? gt(c.close(), c.vwap()) : gt(c.vwap(), c.close());

    List<DotScore> dots = new ArrayList<>();
    add(dots, "vwap", W_VWAP, vwapSide, "price vs VWAP (decisive)");
    add(dots, "supertrend", W, ce ? c.supertrendDir() > 0 : c.supertrendDir() < 0, "supertrend direction");
    add(dots, "vwma", W, ce ? gt(c.close(), c.vwma20()) : gt(c.vwma20(), c.close()), "price vs VWMA20");
    add(dots, "psar", W, ce ? gt(c.close(), c.psar()) : gt(c.psar(), c.close()), "price vs PSAR");
    add(dots, "rsi", W, ScalperGates.rsiBand(c.rsi14(), side).pass(), "RSI band");
    add(dots, "volume", W, ScalperGates.volume(ctx.signalIndex(), c.volume()).pass(), "volume floor");
    add(dots, "futures_oi", W_OI, ScalperGates.oiQuadrant(oi, side).pass(), "futures OI quadrant");
    add(dots, "underlying_oi", W, ce ? oi.underlying().bullish() : oi.underlying().bearish(), "underlying OI quadrant");
    // T2.2: the trending cross is a CHANGE (PE-OI rising while CE-OI falls), not a static PE-CE tilt.
    add(dots, "trending_cross", W, trendingCross(oi, ce), "trending OI cross (dOI change)");
    add(dots, "sentiment", W, sideSigned(oi.sentimentPct(), ce), "active-strike sentiment");
    // T2.6: a DRASTIC dOI move on BOTH legs, imbalanced toward the side.
    add(dots, "drastic_oi", W, drasticOi(oi, ce, props), "drastic dOI both legs, imbalance favors side");
    // T2.3: the sentiment is TRENDING the side's way (slope sign), alongside the level dot above.
    add(dots, "sentiment_slope", W, sideSigned(oi.sentimentSlope(), ce), "sentiment slope direction");
    // T2.7: an OI spurt matching the side's quadrant with both magnitudes past the floor.
    add(dots, "oi_spurt", W, oiSpurt(oi, ce, props), "OI spurt quadrant + magnitude");
    add(dots, "breadth", W, ScalperGates.breadth(m, side).pass(), "advances/declines > 32");
    add(dots, "vix", W, ScalperGates.vix(m, side).pass(), "VIX direction");
    add(dots, "basis", W, ScalperGates.futuresBasis(oi, side).pass(), "futures basis");
    // P3 (signal-analysis rollup §Proposals): ivRank is honest-NULL on every live row (no IV-rank
    // source yet). A null INPUT is WITHHELD from the denominator (absent) — it neither supports nor
    // opposes — rather than silently scoring supports=false against every candidate; a present rank
    // keeps the "IV rank low = cheap premium" grade.
    boolean ivRankAbsent = m.ivRank() == null;
    add(dots, "iv_rank", W_IV, !ivRankAbsent && m.ivRank().compareTo(IV_RANK_LOW) < 0,
        ivRankAbsent ? "IV rank absent (no data — withheld)" : "IV rank low (cheap premium)", ivRankAbsent);
    // T2.8: the side's IV richer than the other by >= the gap; 40/40-both-high forces a stand-aside.
    // E4 iv-per-strike: a UNILATERAL buy-side IV>=40 ("buyer stays away", §4.6) also forces the
    // stand-aside when armed — the existing symmetric ivBothHighStandAside misses the one-sided case.
    boolean buySideTooRich = ivPerStrikeGate && buySideRich(m, ce, props);
    boolean standAside = ivBothHighStandAside(m, props) || buySideTooRich;
    add(dots, "iv_pair", W_IV, !standAside && ivPair(m, ce, props),
        standAside ? "iv pair 40/40 stand-aside" : "iv pair gap favors side");
    if (ivPerStrikeGate) {
      // E4 §4.6: the bought strike's IV DIRECTION — CE confirms when its strike IV is RISING (a buyer
      // paying up = demand), PE when the PE-leg IV rises. Null slope never confirms.
      boolean ivSlopeOk = ce
          ? m.ceIvSlope() != null && m.ceIvSlope().signum() > 0
          : m.peIvSlope() != null && m.peIvSlope().signum() > 0;
      add(dots, "iv_slope", W_IV, ivSlopeOk, "per-strike IV rising on the buy side");
      // E4 §4.6: the absolute ATM IV sits in the 10-12 "trend-play" band (low IV = most of the move
      // still ahead). Null atmIv never confirms.
      boolean ivAbsOk = m.atmIv() != null
          && m.atmIv().compareTo(props.ivAbsBandLow()) >= 0
          && m.atmIv().compareTo(props.ivAbsBandHigh()) <= 0;
      add(dots, "iv_abs_band", W_IV, ivAbsOk, "ATM IV in 10-12 trend-play band");
    }
    if (premiumSkewDot) {
      // E7 §3.7/§6.7 (Hero-Zero): a WARNING dot. supports (good) when the traded side is NOT the richer
      // side, OR it is richer but a positive cue (trending_cross / oi_spurt for the side) corroborates.
      // "Higher-premium side with no cues" → does NOT support → lowers the aggregate (discourages the
      // chase). Null skew → neutral (supports), so a missing feed never blocks. premiumSkewPct > 0 ⇒ CE
      // is the richer side, < 0 ⇒ PE is (the producer's (CE−PE)/PE orientation).
      boolean richerSide =
          m.premiumSkewPct() != null
              && (ce ? m.premiumSkewPct().signum() > 0 : m.premiumSkewPct().signum() < 0);
      boolean cued = corroboratingCue(dots, ce);
      add(dots, "premium_skew", W, m.premiumSkewPct() == null || !richerSide || cued,
          "not chasing the richer side without cues");
    }
    if (dowDot) {
      // E3 Dow global cue: CE confirmed when Dow is UP, PE when DOWN; an unknown direction (null, e.g. a
      // history/off-hours/unconfigured feed) is NEUTRAL → supports, so a missing cue never blocks.
      boolean dowOk = m.dowUp() == null || (ce == m.dowUp());
      add(dots, "dow", W, dowOk,
          "Dow global cue " + (m.dowUp() == null ? "unknown" : m.dowUp() ? "up" : "down"));
    }

    // P3: an ABSENT dot (null input, e.g. the honest-null iv_rank) is withheld from BOTH num and den —
    // neither support nor opposition — so a data gap never dilutes the composite. In the degenerate case
    // where EVERY dot is absent the denominator is 0 and the aggregate is ZERO → below any positive
    // threshold → neither bullish nor bearish (fail-closed no-confluence-support), the same guard an
    // empty dot list hits. In practice the decisive VWAP dot is never absent, so den > 0 on the live path.
    double num = 0;
    double den = 0;
    for (DotScore d : dots) {
      if (d.absent()) {
        continue;
      }
      den += d.weight();
      if (d.supports()) {
        num += d.weight();
      }
    }
    BigDecimal aggregate =
        den == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(num / den).setScale(4, RoundingMode.HALF_UP);

    boolean biasAligned = bias60mDir == 0 || (ce ? bias60mDir > 0 : bias60mDir < 0);
    // VWAP is decisive by default; the #9 opening-tick-before-10:30 path drops it from the HARD gate
    // (vwapHardGate=false) so the OI/sentiment confluence carries the signal — VWAP stays a soft dot.
    boolean valid =
        (!vwapHardGate || vwapSide) && biasAligned && !standAside && aggregate.compareTo(threshold) >= 0;
    return new Confluence(
        aggregate, side, valid && ce, valid && !ce, vwapSide, biasAligned, standAside, dots);
  }

  /**
   * T2.2 trending OI cross as a CHANGE: the side supports only when the PE/CE OI deltas have
   * crossed (or the gap is widening) AND the rising/falling pair favours the side — CE wants PE-OI
   * rising while CE-OI falls (writers building put support), PE the mirror. Null deltas never confirm.
   */
  private static boolean trendingCross(Oi oi, boolean ce) {
    if (!(oi.crossedThisWindow() || oi.gapWidening())
        || oi.peOiDelta() == null
        || oi.ceOiDelta() == null) {
      return false;
    }
    return ce
        ? oi.peOiDelta().signum() > 0 && oi.ceOiDelta().signum() < 0
        : oi.ceOiDelta().signum() > 0 && oi.peOiDelta().signum() < 0;
  }

  /**
   * T2.6 drastic dOI: BOTH legs move at least {@code props.drasticFloor} (absolute) and the
   * imbalance favours the side — CE when PE-OI grew more than CE-OI, PE the mirror. Null deltas →
   * no support.
   */
  private static boolean drasticOi(Oi oi, boolean ce, ScalperOiProps props) {
    if (oi.ceOiDelta() == null || oi.peOiDelta() == null) {
      return false;
    }
    boolean bothDrastic =
        oi.ceOiDelta().abs().compareTo(props.drasticFloor()) >= 0
            && oi.peOiDelta().abs().compareTo(props.drasticFloor()) >= 0;
    boolean favorsSide =
        ce
            ? oi.peOiDelta().compareTo(oi.ceOiDelta()) > 0
            : oi.ceOiDelta().compareTo(oi.peOiDelta()) > 0;
    return bothDrastic && favorsSide;
  }

  /**
   * T2.7 OI spurt: the spurt quadrant matches the side AND both the OI% and price% magnitudes clear
   * their floors (direction comes from the quadrant; magnitude via abs). Null magnitudes → no support.
   */
  private static boolean oiSpurt(Oi oi, boolean ce, ScalperOiProps props) {
    if (oi.spurtOiPct() == null || oi.spurtPricePct() == null) {
      return false;
    }
    boolean quadrant = ce ? oi.underlying().bullish() : oi.underlying().bearish();
    return quadrant
        && oi.spurtOiPct().abs().compareTo(props.spurtOiPct()) >= 0
        && oi.spurtPricePct().abs().compareTo(props.spurtPricePct()) >= 0;
  }

  /**
   * T2.8 IV pair: CE supports when the CE 6-strike IV exceeds the PE by at least the gap, PE the
   * mirror. Null averages → no support. (The 40/40 stand-aside is checked separately and overrides.)
   */
  private static boolean ivPair(Macro m, boolean ce, ScalperOiProps props) {
    if (m.ceIvAvg6() == null || m.peIvAvg6() == null) {
      return false;
    }
    BigDecimal gap =
        ce ? m.ceIvAvg6().subtract(m.peIvAvg6()) : m.peIvAvg6().subtract(m.ceIvAvg6());
    return gap.compareTo(props.ivPairMinGap()) >= 0;
  }

  /**
   * T2.8 stand-aside: both the CE and PE 6-strike IVs are >= the both-high floor AND their gap is
   * under the min gap (richly-priced chop with no directional IV edge) — suppress the whole signal.
   */
  private static boolean ivBothHighStandAside(Macro m, ScalperOiProps props) {
    if (m.ceIvAvg6() == null || m.peIvAvg6() == null) {
      return false;
    }
    boolean bothHigh =
        m.ceIvAvg6().compareTo(props.ivBothHighFloor()) >= 0
            && m.peIvAvg6().compareTo(props.ivBothHighFloor()) >= 0;
    return bothHigh
        && m.ceIvAvg6().subtract(m.peIvAvg6()).abs().compareTo(props.ivPairMinGap()) < 0;
  }

  /**
   * E4 §4.6 unilateral buyer cap: the BUY side's 6-strike IV is >= the both-high floor (40) — "IV>40,
   * buyer stays away" — so a long-premium entry on that side stands aside. Null average → not too rich.
   */
  private static boolean buySideRich(Macro m, boolean ce, ScalperOiProps props) {
    BigDecimal buyIv = ce ? m.ceIvAvg6() : m.peIvAvg6();
    return buyIv != null && buyIv.compareTo(props.ivBothHighFloor()) >= 0;
  }

  private static void add(List<DotScore> dots, String name, double weight, boolean supports, String reason) {
    dots.add(new DotScore(name, weight, supports, reason));
  }

  /** As {@link #add} but marks the dot {@code absent} (withheld from the aggregate) when its input is null. */
  private static void add(
      List<DotScore> dots, String name, double weight, boolean supports, String reason, boolean absent) {
    dots.add(new DotScore(name, weight, supports, reason, absent));
  }

  /**
   * E7: a "positive cue" for the side already in the dot list — a supporting {@code trending_cross} or
   * {@code oi_spurt}. These are added before the premium-skew dot, so scanning the built list is exact.
   */
  private static boolean corroboratingCue(List<DotScore> dots, boolean ce) {
    for (DotScore d : dots) {
      if (d.supports() && ("trending_cross".equals(d.dot()) || "oi_spurt".equals(d.dot()))) {
        return true;
      }
    }
    return false;
  }

  /** CE wants the value positive (put-heavy / PE-OI rising), PE wants it negative. */
  private static boolean sideSigned(BigDecimal value, boolean ce) {
    if (value == null) {
      return false;
    }
    return ce ? value.signum() > 0 : value.signum() < 0;
  }

  private static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }
}
