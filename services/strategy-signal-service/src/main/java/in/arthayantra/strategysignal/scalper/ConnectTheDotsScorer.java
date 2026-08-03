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
  // T2.8 40/40 stand-aside gap: stays at the ORIGINAL 10-IV-pt calibration, decoupled from the
  // ops-tunable iv-pair SUPPORT min-gap (recalibrated 0.10 -> 0.02, rollup §Proposals P1 #675) so
  // activating the dead support dot cannot weaken the high-IV chop suppressor as a side effect.
  private static final BigDecimal BOTH_HIGH_STAND_ASIDE_GAP = new BigDecimal("0.10");

  /**
   * One confluence dot's contribution. {@code absent} (signal-analysis rollup §Proposals P3) marks a
   * dot whose INPUT data is MISSING: it is WITHHELD from BOTH the numerator and the denominator (it
   * neither supports nor opposes), so a data gap is never scored as evidence against the side.
   *
   * <p><b>{@code absent} IS serialized (F5 U4a / dead-dot A6).</b> It originally rode as a
   * scoring-only field — the three live-only side-channels wrote {@code dot}/{@code weight}/{@code
   * supports} (+{@code reason} on the two diagnostics) and the exclusion showed up ONLY as a
   * correspondingly higher aggregate. Since a withheld dot ALSO reads {@code supports=false}, that
   * made "no data" and "data said no" byte-identical in the persisted forensics, and the G13 iv-bloc
   * counterfactual had to reverse-engineer absentness from the effective weight sum being 18.80
   * rather than 19.60. All three serializers now emit the flag, so the fact is recorded, not
   * inferred. This is a diagnostic key only: the aggregate arithmetic here is untouched, and none of
   * the three columns is on the golden/parity path ({@code GoldenSignalsJson.write} lives in
   * {@code libs/strategy-engine}, which never sees this class).
   *
   * <p><b>{@code inputMissing} (F5 U4b) is NOT {@code absent}.</b> It is the raw fact "this dot's
   * INPUT was unavailable this bar", recorded for EVERY dot under BOTH {@link NullPolicy} values;
   * {@code absent} is the SCORING consequence. Under {@link NullPolicy#LEGACY} only {@code iv_rank}
   * turns one into the other — every other missing input still scores {@code supports=false} in the
   * denominator, or (for {@code vix}/{@code basis}/{@code premium_skew}/{@code dow}) as a PASS — and
   * that disagreement is exactly the inconsistency U4b measures. Deliberately NOT serialized: the
   * unarmed side-channels must stay byte-identical, so it lives only in memory, for the aggregate
   * arithmetic below and the {@code dot-null-withheld} shadow variant.
   */
  public record DotScore(
      String dot, double weight, boolean supports, String reason, boolean absent,
      boolean inputMissing) {

    /** Present-dot form: {@code absent}/{@code inputMissing} default to false. */
    public DotScore(String dot, double weight, boolean supports, String reason) {
      this(dot, weight, supports, reason, false, false);
    }

    /** Pre-U4b 5-arg form: {@code inputMissing} defaults to false (keeps existing literals intact). */
    public DotScore(String dot, double weight, boolean supports, String reason, boolean absent) {
      this(dot, weight, supports, reason, absent, false);
    }
  }

  /**
   * The aggregate confluence verdict for a side. {@code standAside} is the T2.8 40/40 both-IV-high
   * suppression: when set, the confluence is forced invalid regardless of the aggregate (the
   * iv-pair dot also withholds support), so a high-IV/low-edge chop never fires.
   *
   * <p>{@code withheldAggregate} (F5 U4b) is the SHADOW number: the same arithmetic with every
   * {@code inputMissing} dot ALSO withheld. It is computed on every bar under BOTH {@link NullPolicy}
   * values and read by NOTHING on the live path — {@code bullish}/{@code bearish} resolve from
   * {@code aggregate} alone — so it is what the composite WOULD have been under the unified rule,
   * available for the {@code dot-null-withheld} shadow variant to put a PnL label on the proposal
   * before anyone arms it. Under {@link NullPolicy#WITHHELD} it equals {@code aggregate} by
   * construction.
   *
   * <p>{@code decisiveLegsHeld} is the other half of that counterfactual: the conjunction of the
   * POLICY-INDEPENDENT decisive legs (hard-VWAP alignment, 60m bias, no IV stand-aside, and — when
   * the DEFAULT-OFF {@code dot-coverage-floor} is armed — sufficient dot-plane data coverage) that
   * {@code valid} requires ALONGSIDE the scalar. A consumer asking "would the armed policy have
   * fired?" must read {@code decisiveLegsHeld && withheldAggregate >= threshold} — the scalar alone
   * is NOT the verdict, and bars where it clears while a decisive leg blocks are observed live.
   */
  public record Confluence(
      BigDecimal aggregate, OptionType side, boolean bullish, boolean bearish,
      boolean vwapAligned, boolean biasAligned, boolean standAside, List<DotScore> dots,
      BigDecimal withheldAggregate, boolean decisiveLegsHeld) {

    /**
     * Pre-U4b 8-arg form: {@code withheldAggregate} mirrors {@code aggregate} (no shadow recorded)
     * and {@code decisiveLegsHeld} is FALSE — fail-closed, so a hand-built or legacy confluence can
     * never let a counterfactual consumer conclude the armed policy would have fired.
     */
    public Confluence(
        BigDecimal aggregate, OptionType side, boolean bullish, boolean bearish,
        boolean vwapAligned, boolean biasAligned, boolean standAside, List<DotScore> dots) {
      this(
          aggregate, side, bullish, bearish, vwapAligned, biasAligned, standAside, dots, aggregate,
          false);
    }
  }

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
    return score(
        ctx, side, bias60mDir, threshold, props, vwapHardGate, ivPerStrikeGate, premiumSkewDot,
        dowDot, null);
  }

  /**
   * As the 9-arg form but with the RESOLVED §0B volume floor threaded in (T24, root-caused
   * 2026-07-28).
   *
   * <p><b>The defect this closes.</b> The {@code volume} dot called the TWO-argument
   * {@code ScalperGates.volume(underlying, volume)}, which resolves the floor through
   * {@code volumeFloorFor(underlying, null)} — i.e. the STATIC per-index default (NIFTY 125,000).
   * The {@code relative-volume-floor} tag substitutes the banded floor at the RAIL call site only
   * ({@code ScalperConfluenceGate}), so the dot never saw it. The tag has been armed on all 21 NIFTY
   * scalpers since #605 and the dot has been reading a floor it could not clear: on 2026-07-27 the
   * 3m series max was 117,000, so ZERO bars could cross 125,000 and the dot scored 0/909; on
   * 2026-07-28 only expiry-day churn got it to 38/1,068 (3.6%), its first non-zero reading in nine
   * sessions. 1.0 of weight had been gated at roughly p95 of its own operand, every strategy, every
   * session.
   *
   * <p>{@code volumeFloor} null ⇒ the per-index default, i.e. byte-identical to the 9-arg form, so
   * every other caller and every unarmed strategy is unmoved. Only a strategy that actually resolves
   * a different floor changes — which is the point, and which is why this is HOLD tier: it raises
   * the composite for armed strategies and therefore changes which signals fire.
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate, boolean premiumSkewDot,
      boolean dowDot, BigDecimal volumeFloor) {
    return score(
        ctx, side, bias60mDir, threshold, props, vwapHardGate, ivPerStrikeGate, premiumSkewDot,
        dowDot, volumeFloor, false);
  }

  /**
   * As the 10-arg form but with the {@code iv_rank} dot opted in via the {@code iv-rank-dot} tag (A3,
   * Architect 2026-08-01).
   *
   * <p><b>The calendar self-arm this closes.</b> {@code IvAnalyticsService} suppresses the rank below
   * {@code artha.iv.rank-history-floor-days} (60 trading days) of {@code marketdata.iv_daily_summary}
   * history — an intentional honesty floor. Live capture began 2026-06-15, so the input has been
   * honest-NULL on every row so far and the dot has read absent for 13+ straight sessions. Once the
   * floor is reached the rank starts resolving on its own, and this 0.8-weight dot would SILENTLY enter
   * the composite denominator fleet-wide (18.80 → 19.60) on a CALENDAR trigger — no deploy, no owner
   * arming decision. That is exactly the class of change the repo's default-OFF doctrine exists to
   * prevent, so activation is now an explicit arming event (tag → new version → publish).
   *
   * <p>{@code ivRankDot} false ⇒ the dot is ABSENT — withheld from BOTH the numerator and the
   * denominator, precisely as a null input already is — so the unarmed reading is byte-identical to the
   * 10-arg form on today's data (the input is null everywhere, live and historical) and inert in BOTH
   * directions once it is not (a would-oppose rank never drags the aggregate down; a would-support rank
   * never props it up). The dot stays IN the list marked absent, so the recorded side-channel keeps its
   * shape.
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate, boolean premiumSkewDot,
      boolean dowDot, BigDecimal volumeFloor, boolean ivRankDot) {
    return score(
        ctx, side, bias60mDir, threshold, props, vwapHardGate, ivPerStrikeGate, premiumSkewDot,
        dowDot, volumeFloor, ivRankDot, NullPolicy.LEGACY);
  }

  /**
   * As the 11-arg form but with the missing-input {@link NullPolicy} selected, via the DEFAULT-OFF
   * {@code dot-null-withheld} tag (F5 U4b).
   *
   * <p><b>The inconsistency this closes.</b> Three unreconciled missing-input rules coexist in the
   * dot list — see {@link NullPolicy} for the full map. The same data gap therefore HELPS the side on
   * {@code vix}/{@code basis}/{@code premium_skew}/{@code dow}, HURTS it on all remaining ENABLED
   * dots (fifteen of the default eighteen, plus the two the {@code iv-per-strike} tag adds — {@code
   * iv_slope} and {@code iv_abs_band} are opponent-on-missing too, and that tag IS armed on live
   * Connecting-Dots configs, so the real count is seventeen there) (the
   * {@link OiQuadrant#NEUTRAL} "snapshot unavailable" sentinel included), and VANISHES on
   * {@code iv_rank}. {@link NullPolicy#WITHHELD} makes all three the third rule: an input-missing dot
   * leaves both the numerator and the denominator, so a data gap is never evidence either way.
   *
   * <p>{@link NullPolicy#LEGACY} (the default) is byte-identical to the 11-arg form: the dot list,
   * every dot's {@code absent}/{@code supports}/{@code reason}, and the aggregate are unchanged, and
   * only the never-read {@code withheldAggregate} side-channel is added. Arming CHANGES which signals
   * fire — a scalper config change is a silent no-op until the strategy is RE-PUBLISHED.
   *
   * <p><b>This moves the confluence aggregate, NOT the frozen A1 {@code ScoreBreakdownDto} composite</b>
   * ({@code composite = Σ(w·s)/Σw} + the optional-activation rule). The confluence is the §12.3
   * side-channel number this class owns; the frozen breakdown lives in the engine and never sees it.
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate, boolean premiumSkewDot,
      boolean dowDot, BigDecimal volumeFloor, boolean ivRankDot, NullPolicy nullPolicy) {
    return score(
        ctx, side, bias60mDir, threshold, props, vwapHardGate, ivPerStrikeGate, premiumSkewDot,
        dowDot, volumeFloor, ivRankDot, nullPolicy, false);
  }

  /**
   * As the 12-arg form but with the F5 U4b §5.3 DATA-COVERAGE FLOOR opted in, via the DEFAULT-OFF
   * {@code dot-coverage-floor} tag (the {@code dot-null-withheld} tag implies it — see below).
   *
   * <p><b>Why the floor exists.</b> {@link NullPolicy#WITHHELD} has one perverse property: the LESS
   * data arrives, the HIGHER the composite can go, because the denominator shrinks faster than the
   * numerator whenever the surviving dots agree. The degenerate all-absent case is already
   * fail-closed ({@link #ratio} returns ZERO on an empty denominator), but the INTERMEDIATE case is
   * not. Measured over the 11,068 post-P3 scored evaluations (decision sheet
   * {@code docs/signal-analysis/2026-08-03-dot-null-semantics-decision.md} §5.3): on 2026-07-20 (the
   * TimescaleDB 2.18.2 planner outage) and 2026-07-28 (the NSE monthly expiry, where
   * {@code MarketOiClient.oi()} suppresses the whole OI block BY DESIGN) withholding would have
   * RAISED the aggregate on 1,812 of 1,816 rows. Nothing fired only because a decisive leg happened
   * to block — that is the tape's luck, not a guarantee. <b>Withhold without a coverage floor
   * converts a data outage into a reason to trade MORE</b>, which is precisely the direction the
   * standing prior (every measured loosening of the scalper entry gate has lost money) forbids.
   *
   * <p><b>The floor is a natural break, not a fitted parameter.</b> The measured distribution of
   * {@code surviving weight / legacy baseline weight} is 9,207 rows at exactly 1.000, 45 rows in
   * 0.947–0.961 (exactly one dot missing), then ZERO rows until 0.828, below which sit the 748
   * outage rows and the 1,068 expiry rows. Any floor in [0.85, 0.94] separates "one dot happened to
   * be missing" from "a whole data plane is gone" with no row in the gap; {@code 0.90}
   * ({@link ScalperOiProps#dotCoverageFloor()}) sits dead centre.
   *
   * <p><b>DEFAULT-OFF and never the policy alone.</b> {@code coverageFloorGate} false is
   * byte-identical to the 12-arg form. {@link ScalperConfluenceGate} arms it from
   * {@code dot-coverage-floor} OR {@code dot-null-withheld}, so the unified null rule can never be
   * armed WITHOUT its floor — the doctrine is structural rather than a convention someone has to
   * remember. The floor alone (LEGACY policy) is a pure TIGHTENING and safe to measure first.
   */
  public static Confluence score(
      ScalperGateContext ctx, OptionType side, int bias60mDir, BigDecimal threshold,
      ScalperOiProps props, boolean vwapHardGate, boolean ivPerStrikeGate, boolean premiumSkewDot,
      boolean dowDot, BigDecimal volumeFloor, boolean ivRankDot, NullPolicy nullPolicy,
      boolean coverageFloorGate) {
    Chart c = ctx.chart();
    Oi oi = ctx.oi();
    Macro m = ctx.macro();
    boolean ce = side == OptionType.CE;
    boolean withhold = nullPolicy.withholds();

    boolean vwapSide = ce ? gt(c.close(), c.vwap()) : gt(c.vwap(), c.close());

    // U4b: the per-dot "its INPUT was unavailable" reads. Single-sourced as locals where two dots
    // share an input, so the WITHHELD policy and the always-computed shadow aggregate below judge
    // exactly the same fact, and a future dot cannot drift from its neighbours' definition.
    // `supertrendDir == 0` and `OiQuadrant.NEUTRAL` are the no-data sentinels of an int / enum
    // input. NEUTRAL says so in its own javadoc. For supertrend, 0 is UNAMBIGUOUSLY no-data:
    // `Ta4jIndicators.supertrendDirection` (:48-69) emits only ±1 (a boolean isUpTrend picks it)
    // and null only when unwarmed or out of range, and the sentinel's sole producer is
    // `ScalperConfluenceGate:1271`, `supertrend == null ? 0 : supertrend.signum()` — signum() of ±1
    // is never 0. (A "mid-flip" third state does not exist; ScalperGates said it did and was wrong.)
    // breadth's int pair reads {0,0} exactly when the summary was absent (MarketOiClient#942).
    boolean closeMissing = c.close() == null;
    boolean oiDeltasMissing = oi.ceOiDelta() == null || oi.peOiDelta() == null;
    boolean underlyingQuadrantMissing = oi.underlying() == OiQuadrant.NEUTRAL;
    boolean ivPairMissing = m.ceIvAvg6() == null || m.peIvAvg6() == null;

    List<DotScore> dots = new ArrayList<>();
    // T6 (owner 2026-07-25): the DOT needs a real distance, not just the side — the entry gate
    // already enforces the side, so a side-only dot supported 100% of 5,225 rows across six
    // sessions (an unlabelled −12.8% threshold cut at the heaviest weight). The DECISIVE hard-gate
    // leg below (`valid`, via vwapSide) is untouched.
    add(dots, "vwap", W_VWAP,
        vwapSide && vwapDistanceAtLeast(c.close(), c.vwap(), props.vwapMinDistanceBps()),
        "price vs VWAP (side + >=" + props.vwapMinDistanceBps() + " bps)",
        closeMissing || c.vwap() == null, withhold);
    add(dots, "supertrend", W, ce ? c.supertrendDir() > 0 : c.supertrendDir() < 0, "supertrend direction",
        c.supertrendDir() == 0, withhold);
    add(dots, "vwma", W, ce ? gt(c.close(), c.vwma20()) : gt(c.vwma20(), c.close()), "price vs VWMA20",
        closeMissing || c.vwma20() == null, withhold);
    add(dots, "psar", W, ce ? gt(c.close(), c.psar()) : gt(c.psar(), c.close()), "price vs PSAR",
        closeMissing || c.psar() == null, withhold);
    add(dots, "rsi", W, ScalperGates.rsiBand(c.rsi14(), side).pass(), "RSI band",
        c.rsi14() == null, withhold);
    // T24: the RESOLVED floor — the same value the rail tested — not the static per-index default.
    add(dots, "volume", W,
        ScalperGates.volume(ctx.signalIndex(), c.volume(), volumeFloor).pass(), "volume floor",
        c.volume() == null, withhold);
    add(dots, "futures_oi", W_OI, ScalperGates.oiQuadrant(oi, side).pass(), "futures OI quadrant",
        oi.futures() == OiQuadrant.NEUTRAL, withhold);
    add(dots, "underlying_oi", W, ce ? oi.underlying().bullish() : oi.underlying().bearish(),
        "underlying OI quadrant", underlyingQuadrantMissing, withhold);
    // T2.2: the trending cross is a CHANGE (PE-OI rising while CE-OI falls), not a static PE-CE tilt.
    // (`crossedThisWindow`/`gapWidening` are real false readings, not gaps — only the deltas can be absent.)
    add(dots, "trending_cross", W, trendingCross(oi, ce), "trending OI cross (dOI change)",
        oiDeltasMissing, withhold);
    add(dots, "sentiment", W, sideSigned(oi.sentimentPct(), ce), "active-strike sentiment",
        oi.sentimentPct() == null, withhold);
    // T2.6: a DRASTIC dOI move on BOTH legs, imbalanced toward the side.
    add(dots, "drastic_oi", W, drasticOi(oi, ce, props), "drastic dOI both legs, imbalance favors side",
        oiDeltasMissing, withhold);
    // T2.3: the sentiment is TRENDING the side's way (slope sign), alongside the level dot above.
    add(dots, "sentiment_slope", W, sideSigned(oi.sentimentSlope(), ce), "sentiment slope direction",
        oi.sentimentSlope() == null, withhold);
    // T2.7: an OI spurt matching the side's quadrant with both magnitudes past the floor. The QUADRANT
    // is one of its inputs, so a NEUTRAL underlying leaves this dot input-missing too.
    add(dots, "oi_spurt", W, oiSpurt(oi, ce, props), "OI spurt quadrant + magnitude",
        oi.spurtOiPct() == null || oi.spurtPricePct() == null || underlyingQuadrantMissing, withhold);
    add(dots, "breadth", W, ScalperGates.breadth(m, side).pass(), "advances/declines > 32",
        m.advances() == 0 && m.declines() == 0, withhold);
    add(dots, "vix", W, ScalperGates.vix(m, side).pass(), "VIX direction",
        m.vixRising() == null, withhold);
    add(dots, "basis", W, ScalperGates.futuresBasis(oi, side).pass(), "futures basis",
        oi.futuresBasis() == null, withhold);
    // P3 (signal-analysis rollup §Proposals): ivRank is honest-NULL on every live row (the 60-trading-
    // day IvAnalyticsService history floor is not met yet). A null INPUT is WITHHELD from the
    // denominator (absent) — it neither supports nor opposes — rather than silently scoring
    // supports=false against every candidate; a present rank keeps the "IV rank low = cheap premium"
    // grade. A3: and the dot is DEFAULT-OFF behind `iv-rank-dot`, so reaching that floor cannot
    // self-arm it on a calendar trigger — the unarmed dot is withheld exactly as a null input is.
    boolean ivRankNull = m.ivRank() == null;
    boolean ivRankAbsent = ivRankNull || !ivRankDot;
    // §5.3 coverage baseline: the weight the LEGACY policy puts in the denominator — every enabled
    // dot MINUS whatever LEGACY itself already withholds. `iv_rank` is the only such dot today: its
    // `absent` is set here rather than by `add(...)`'s `withhold && inputMissing`, covering BOTH the
    // honest-null input and the unarmed `iv-rank-dot` gate, and it reads absent on 100% of live
    // rows. Normalizing it out is what the decision sheet §5.3 measured, and where the empty
    // [0.828, 0.947] band that locates the 0.90 floor was found — a baseline that counted a
    // permanently-null dot as missing coverage would put a clean bar at 0.959, not 1.000, and the
    // floor would no longer sit in a gap. ⚠️ A FUTURE dot that sets its own `absent` outside
    // `add(...)` must subtract its weight here too; `coverageIsIdenticalUnderBothNullPolicies`
    // pins the policy-independence half of that contract.
    double legacyWithheldWeight = ivRankAbsent ? W_IV : 0;
    // U4b: iv_rank is the ONE dot already on the unified rule, so its `absent` is untouched by the
    // policy — a null input is withheld under both. The GATE absence (unarmed) is not an input gap,
    // so `inputMissing` tracks only the null, keeping the shadow aggregate honest.
    dots.add(
        new DotScore(
            "iv_rank", W_IV, !ivRankAbsent && m.ivRank().compareTo(IV_RANK_LOW) < 0,
            ivRankReason(ivRankNull, ivRankDot), ivRankAbsent, ivRankNull));
    // T2.8: the side's IV richer than the other by >= the gap; 40/40-both-high forces a stand-aside.
    // E4 iv-per-strike: a UNILATERAL buy-side IV>=40 ("buyer stays away", §4.6) also forces the
    // stand-aside when armed — the existing symmetric ivBothHighStandAside misses the one-sided case.
    boolean buySideTooRich = ivPerStrikeGate && buySideRich(m, ce, props);
    boolean standAside = ivBothHighStandAside(m, props) || buySideTooRich;
    add(dots, "iv_pair", W_IV, !standAside && ivPair(m, ce, props),
        standAside ? "iv pair 40/40 stand-aside" : "iv pair gap favors side", ivPairMissing, withhold);
    if (ivPerStrikeGate) {
      // E4 §4.6: the bought strike's IV DIRECTION — CE confirms when its strike IV is RISING (a buyer
      // paying up = demand), PE when the PE-leg IV rises. Null slope never confirms.
      boolean ivSlopeOk = ce
          ? m.ceIvSlope() != null && m.ceIvSlope().signum() > 0
          : m.peIvSlope() != null && m.peIvSlope().signum() > 0;
      add(dots, "iv_slope", W_IV, ivSlopeOk, "per-strike IV rising on the buy side",
          (ce ? m.ceIvSlope() : m.peIvSlope()) == null, withhold);
      // E4 §4.6: the absolute ATM IV sits in the 10-12 "trend-play" band (low IV = most of the move
      // still ahead). Null atmIv never confirms.
      boolean ivAbsOk = m.atmIv() != null
          && m.atmIv().compareTo(props.ivAbsBandLow()) >= 0
          && m.atmIv().compareTo(props.ivAbsBandHigh()) <= 0;
      add(dots, "iv_abs_band", W_IV, ivAbsOk, "ATM IV in 10-12 trend-play band",
          m.atmIv() == null, withhold);
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
          "not chasing the richer side without cues", m.premiumSkewPct() == null, withhold);
    }
    if (dowDot) {
      // E3 Dow global cue: CE confirmed when Dow is UP, PE when DOWN; an unknown direction (null, e.g. a
      // history/off-hours/unconfigured feed) is NEUTRAL → supports, so a missing cue never blocks.
      boolean dowOk = m.dowUp() == null || (ce == m.dowUp());
      add(dots, "dow", W, dowOk,
          "Dow global cue " + (m.dowUp() == null ? "unknown" : m.dowUp() ? "up" : "down"),
          m.dowUp() == null, withhold);
    }

    // P3: an ABSENT dot (null input, e.g. the honest-null iv_rank) is withheld from BOTH num and den —
    // neither support nor opposition — so a data gap never dilutes the composite. In the degenerate case
    // where EVERY dot is absent the denominator is 0 and the aggregate is ZERO → below any positive
    // threshold → neither bullish nor bearish (fail-closed no-confluence-support), the same guard an
    // empty dot list hits. In practice the decisive VWAP dot is never absent, so den > 0 on the live
    // path — and U4b does not weaken that: WITHHELD can only mark vwap absent when close/vwap are
    // null, i.e. there is no real bar, and the hard VWAP gate (`vwapSide` below) already blocks that.
    double num = 0;
    double den = 0;
    // U4b shadow: the SAME arithmetic with every input-missing dot ALSO withheld — what the composite
    // WOULD have been under the unified rule. Computed on every bar under BOTH policies and read by
    // nothing here (`valid` below uses `aggregate` alone), so it can never move live scoring; under
    // WITHHELD the two loops coincide because `absent` already subsumes `inputMissing`.
    double shadowNum = 0;
    double shadowDen = 0;
    double totalWeight = 0;
    for (DotScore d : dots) {
      totalWeight += d.weight();
      if (!d.absent()) {
        den += d.weight();
        if (d.supports()) {
          num += d.weight();
        }
      }
      if (!d.absent() && !d.inputMissing()) {
        shadowDen += d.weight();
        if (d.supports()) {
          shadowNum += d.weight();
        }
      }
    }
    BigDecimal aggregate = ratio(num, den);
    BigDecimal withheldAggregate = ratio(shadowNum, shadowDen);
    // §5.3: how much of the dot plane ACTUALLY had data this bar. `shadowDen` is the surviving
    // weight under the unified rule; the baseline is the LEGACY denominator (see
    // `legacyWithheldWeight`). Deliberately POLICY-INDEPENDENT — `shadowDen` and the baseline are
    // both computed the same way under LEGACY and WITHHELD — because a floor that moved when the
    // policy was armed could not gate the policy, and because the same ratio has to mean one thing
    // on the unarmed rows the shadow lane records. An empty baseline is ZERO ⇒ below any positive
    // floor ⇒ blocks, the same fail-closed direction as `aggregate`.
    BigDecimal coverage = ratio(shadowDen, totalWeight - legacyWithheldWeight);

    boolean biasAligned = bias60mDir == 0 || (ce ? bias60mDir > 0 : bias60mDir < 0);
    // VWAP is decisive by default; the #9 opening-tick-before-10:30 path drops it from the HARD gate
    // (vwapHardGate=false) so the OI/sentiment confluence carries the signal — VWAP stays a soft dot.
    //
    // U4b: the three DECISIVE legs are factored out (same conjunction, `&&` is associative, so
    // `valid` is byte-identical) because they are POLICY-INDEPENDENT — not one of them reads the
    // aggregate. That makes the counterfactual exact: "would the ARMED policy have fired this bar?"
    // is `decisiveLegsHeld && withheldAggregate >= threshold`, never the scalar alone. The
    // distinction is not hypothetical — `ScalperConfluenceGate.compositeMargin` documents 4 live
    // rows (3 on 2026-07-24, 1 on 07-23) where the aggregate CLEARED the threshold while a decisive
    // leg blocked, which is exactly the shape that would let a challenger book a trade the armed
    // policy still rejects.
    //
    // §5.3's floor is the FOURTH such leg, and belongs here for the same reason the other three do:
    // it never reads the aggregate, so the counterfactual stays exact under either policy. Below the
    // floor the confluence is INVALID — refused outright, not merely scored lower — because a
    // vanished data plane is not weak evidence, it is no evidence. DEFAULT-OFF: unarmed,
    // `coverageHeld` is unconditionally true and every leg is byte-identical to before.
    boolean coverageHeld =
        !coverageFloorGate || coverage.compareTo(props.dotCoverageFloor()) >= 0;
    boolean decisiveLegsHeld =
        (!vwapHardGate || vwapSide) && biasAligned && !standAside && coverageHeld;
    boolean valid = decisiveLegsHeld && aggregate.compareTo(threshold) >= 0;
    return new Confluence(
        aggregate, side, valid && ce, valid && !ce, vwapSide, biasAligned, standAside, dots,
        withheldAggregate, decisiveLegsHeld);
  }

  /** The confluence ratio at the frozen 4-dp scale; an empty denominator is ZERO (fail-closed). */
  private static BigDecimal ratio(double num, double den) {
    return den == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(num / den).setScale(4, RoundingMode.HALF_UP);
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
   * under {@link #BOTH_HIGH_STAND_ASIDE_GAP} (richly-priced chop with no directional IV edge) —
   * suppress the whole signal. Deliberately DECOUPLED from the (recalibrated, rollup P1) iv-pair
   * support min-gap: at 40+ IV levels a 2-pt spread is noise, not a directional edge, so narrowing
   * the SUPPORT gap must not narrow this chop suppressor.
   */
  private static boolean ivBothHighStandAside(Macro m, ScalperOiProps props) {
    if (m.ceIvAvg6() == null || m.peIvAvg6() == null) {
      return false;
    }
    boolean bothHigh =
        m.ceIvAvg6().compareTo(props.ivBothHighFloor()) >= 0
            && m.peIvAvg6().compareTo(props.ivBothHighFloor()) >= 0;
    return bothHigh
        && m.ceIvAvg6().subtract(m.peIvAvg6()).abs().compareTo(BOTH_HIGH_STAND_ASIDE_GAP) < 0;
  }

  /**
   * E4 §4.6 unilateral buyer cap: the BUY side's 6-strike IV is >= the both-high floor (40) — "IV>40,
   * buyer stays away" — so a long-premium entry on that side stands aside. Null average → not too rich.
   */
  private static boolean buySideRich(Macro m, boolean ce, ScalperOiProps props) {
    BigDecimal buyIv = ce ? m.ceIvAvg6() : m.peIvAvg6();
    return buyIv != null && buyIv.compareTo(props.ivBothHighFloor()) >= 0;
  }

  /**
   * The {@code iv_rank} dot's reason. The NULL-input case is checked FIRST and keeps its exact wording,
   * so today's rows (null input, dot unarmed) serialize byte-identically to before the A3 gate; the
   * unarmed-but-present case gets its own wording so a September diagnostic reads "unarmed", not the
   * misleading "no data".
   */
  private static String ivRankReason(boolean ivRankNull, boolean ivRankDot) {
    if (ivRankNull) {
      return "IV rank absent (no data — withheld)";
    }
    return ivRankDot ? "IV rank low (cheap premium)" : "IV rank dot unarmed (withheld)";
  }

  /**
   * Adds one dot, recording whether its INPUT was unavailable and letting the resolved
   * {@link NullPolicy} decide the consequence: under {@code withhold} an input-missing dot is marked
   * {@code absent} (out of both the numerator and denominator); under LEGACY it keeps whatever
   * {@code supports} its own rule produced, which is exactly today's behaviour.
   */
  private static void add(
      List<DotScore> dots, String name, double weight, boolean supports, String reason,
      boolean inputMissing, boolean withhold) {
    dots.add(new DotScore(name, weight, supports, reason, withhold && inputMissing, inputMissing));
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

  /**
   * CE wants the value positive (put-heavy / PE-OI rising), PE wants it negative.
   *
   * <p>Package-private rather than private so {@link SentimentLevelShadow} evaluates its
   * counterfactual through the SAME predicate the live {@code sentiment} dot uses, with only the
   * operand swapped — a copy of the rule there could drift from this one and silently mis-measure.
   */
  static boolean sideSigned(BigDecimal value, boolean ce) {
    if (value == null) {
      return false;
    }
    return ce ? value.signum() > 0 : value.signum() < 0;
  }

  private static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }

  // T6: |close − vwap| / close ≥ minBps/10000. Null/zero-close ⇒ false (the side check already
  // failed on null; a zero close is not a real bar).
  private static boolean vwapDistanceAtLeast(BigDecimal close, BigDecimal vwap, BigDecimal minBps) {
    if (close == null || vwap == null || minBps == null || close.signum() == 0) {
      return false;
    }
    // cross-multiplied to avoid division: |close − vwap| * 10000 >= minBps * |close|
    return close.subtract(vwap).abs().movePointRight(4).compareTo(minBps.multiply(close.abs())) >= 0;
  }
}
