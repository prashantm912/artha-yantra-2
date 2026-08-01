package in.arthayantra.strategysignal.signals;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.OiQuadrant;
import in.arthayantra.strategysignal.scalper.ScalperGateContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-row gate-input health, computed at record time (signal-analysis README §7 row 4, F5 unit U3).
 *
 * <p>PURE: a total function of {@code (context, sessionDate)}. No clock, no I/O, no Spring — so it is
 * exhaustively testable and costs nothing to run on the {@link RejectionWriter}'s bounded async
 * thread. It reads the SAME {@link ScalperGateContext} the diagnostic JSON is serialized from, so the
 * flags can never disagree with the row they annotate.
 *
 * <p>The flag set covers EVERY absence-bearing input the scorer and the gates actually consume, not
 * a convenient subset: a partial source failure (say the FII bias drops out while the OI block is
 * perfectly healthy) must name the specific input, because the alternative is a row that reports
 * nothing wrong. A false clean on a data-health surface is worse than no surface — it converts an
 * unknown into a confident wrong answer.
 *
 * <p>That completeness is RATCHETED, not merely documented. {@code DataHealthFlagsTest} reflects
 * over every {@code Macro} and {@code Oi} record component and fails unless each is explicitly
 * classified — either mapped to a flag (and then proved to actually produce it when absent, and to
 * sit in the right S24 group) or exempted with a written reason. Adding a component without
 * deciding is a build failure. This exists because a documentation-only rule already decayed: the
 * first pass at "cover every input" still missed the futures quadrant and the futures basis.
 *
 * <p><b>A flag means the input was ABSENT when the gate scored this bar.</b> It never means "the
 * input had an unremarkable value". That line is where a naive port of {@link DotHealthCanary}'s
 * probes goes wrong, and the difference is not cosmetic: the canary asks aggregate questions over a
 * 40-row window ("did {@code spurtPricePct} move at all today", "was the window all-NEUTRAL"), whose
 * per-row analogues are ordinary market states. Measured on live rows 2026-07-20..31,
 * {@code spurtPricePct = 0} holds on ~43% of context-bearing rows across EVERY session, expiry or
 * not — a flat bar, not a defect. Only unambiguous absence is flagged here.
 *
 * <p>Rows blocked before the chain fetch carry no context at all (~23% of live rows: the
 * time-window / time-of-day / option-side rails). Those are UNINFORMATIVE, not degraded —
 * {@code contextBearing=false}, no flags, not degraded. T17's lesson (a context-less row cannot
 * testify about dot liveness) applied per row instead of per window.
 *
 * <p><b>The S24 per-root exemption.</b> On a MONTHLY index expiry {@code MarketOiClient.oi()} skips
 * the whole OI block by design — the expiring series' writers are unwinding, so chain OI is corrupt —
 * and returns an inert {@code Oi}: NEUTRAL on both quadrants, null on every soft numeric
 * (MarketOiClient:347-356). Every chain-OI dot is then legitimately non-confirming, so the ENTIRE
 * CHAIN-OI flag group is withheld — never a subset — and {@code oiSuppressed} records why.
 *
 * <p>Two things are deliberately OUTSIDE that group and are still judged on an expiry day. The macro
 * flags, because {@code MarketOiClient.macro()} is untouched by the skip — a dead breadth on an
 * expiry day is as real as on any other. And {@link #FUTURES_BASIS_ABSENT}, because the basis is
 * price-derived and the skip explicitly KEEPS it: withholding it would blind the one field on the
 * {@code Oi} record whose absence the skip does not explain. Group membership is pinned per record
 * component by {@code DataHealthFlagsTest}'s coverage ratchet. ⚠️ The suppression is keyed PER OI
 * ROOT, NOT per
 * date: NSE's monthly index expiry is the last Tuesday, BSE's (SENSEX) the last Thursday, and
 * {@code MarketOiClient} keys on the row's own underlying. On an NSE-only expiry day a SENSEX-rooted
 * OI read is NOT suppressed and a dead OI block there IS a genuine outage — a date-keyed exemption
 * would silence exactly that. This is the misreading that cost a live investigation (#1073) and it
 * was made and corrected once already inside {@code DotHealthCanary} (2026-07-28). Measured
 * confirmation that the signature is exact: across 2026-07-20..31 the inert signature fired on
 * 1,068/1,068 rows on 2026-07-28 (the NSE monthly expiry) and on 0 rows on all nine other sessions.
 *
 * @param degraded true iff {@link #flags()} is non-empty — at least one input was absent
 * @param contextBearing false when the block landed before the chain fetch (no context to judge)
 * @param oiSuppressed true when this row's OI root is on ITS monthly index expiry (S24, by design)
 * @param flags the absent inputs, in a stable declaration order
 */
public record DataHealthFlags(
    boolean degraded, boolean contextBearing, boolean oiSuppressed, List<String> flags) {

  // ---- MACRO inputs. NEVER withheld: MarketOiClient.macro() is untouched by the S24 skip, so a
  // macro absence on an expiry day is as real as on any other day. ------------------------------

  /** Breadth read failed: a real session always has at least one advance or decline. */
  public static final String BREADTH_ABSENT = "breadth-absent";

  /** No ATM IV — the {@code iv_abs_band} dot cannot confirm (ConnectTheDotsScorer:251-254). */
  public static final String ATM_IV_ABSENT = "atm-iv-absent";

  /** No IV rank — the /iv-history daily series was short or unavailable. */
  public static final String IV_RANK_ABSENT = "iv-rank-absent";

  /** No 6-strike CE/PE IV — the {@code iv_pair} dot AND the {@code iv-buyer-cap} risk rail go blind. */
  public static final String IV_PAIR_ABSENT = "iv-pair-absent";

  /** No per-strike IV slope — the {@code iv_slope} dot cannot confirm (ConnectTheDotsScorer:246). */
  public static final String IV_SLOPE_ABSENT = "iv-slope-absent";

  /** No ATM premium skew — the {@code premium_skew} warning dot degrades to neutral (:263-266). */
  public static final String PREMIUM_SKEW_ABSENT = "premium-skew-absent";

  /**
   * No VIX DIRECTION. This — not the level — is the vix dot's verdict-bearing input: a null
   * {@code vixRising} makes {@code ScalperGates.vix} degrade to pass while {@code vixLevel} is
   * merely the reported operand (ScalperGates:596-601).
   */
  public static final String VIX_DIRECTION_ABSENT = "vix-direction-absent";

  /** No India VIX level — the vix dot's reported operand is missing. */
  public static final String VIX_LEVEL_ABSENT = "vix-level-absent";

  /** No FII long % — the {@code fii-flow} gate cannot read (ScalperGates.fiiBias). */
  public static final String FII_ABSENT = "fii-absent";

  /** No combined FII bias sign — the {@code fii-dii-gate} degrades to pass (ScalperGates:819). */
  public static final String FII_BIAS_ABSENT = "fii-bias-absent";

  /** No heavyweight push — the {@code constituent-gate} degrades to pass (ScalperGates:838). */
  public static final String CONSTITUENT_BIAS_ABSENT = "constituent-bias-absent";

  /** No Dow direction — the global cue was unconfigured, off-hours or history. */
  public static final String DOW_ABSENT = "dow-absent";

  /**
   * No futures basis. This lives on the {@code Oi} record but is NOT part of the suppressed group:
   * the basis is PRICE-derived, so {@code MarketOiClient.oi()} deliberately KEEPS it through the S24
   * skip (MarketOiClient:350-355). A null basis on an expiry day is therefore a real failure, and
   * withholding it would blind exactly the one OI-record field the skip does not explain.
   * {@code ScalperGates.futuresBasis} degrades to pass on null (:851-855).
   */
  public static final String FUTURES_BASIS_ABSENT = "futures-basis-absent";

  // ---- CHAIN-OI inputs. ALL of these are withheld together when oiSuppressed, because the S24
  // skip nulls the WHOLE chain read at once — flagging any one of them there reports a defect that
  // the design deliberately caused. See the class javadoc. --------------------------------------

  /**
   * The OI half contributed nothing at all: both quadrants NEUTRAL and every soft numeric null (or
   * no {@code Oi} at all). The whole-block summary that sits alongside the per-field flags below.
   */
  public static final String OI_INERT = "oi-inert";

  /**
   * The futures OI snapshot was unavailable. {@link OiQuadrant#NEUTRAL} is not a market state — its
   * javadoc is explicit that it exists ONLY to represent "data missing" without a null that would
   * NPE the gates. It is the verdict-bearing input of {@code ScalperGates.oiQuadrant} (:582-584,
   * weight 1.5); {@code sentimentPct} beside it is merely the reported operand, so a failed
   * {@code futures/banks} read alongside a healthy sentiment used to read clean.
   */
  public static final String FUTURES_QUADRANT_ABSENT = "futures-quadrant-absent";

  /**
   * The underlying OI snapshot was unavailable — same NEUTRAL-means-missing sentinel, read by the
   * {@code underlying_oi} dot and by {@code oiSpurt}'s quadrant check.
   */
  public static final String UNDERLYING_QUADRANT_ABSENT = "underlying-quadrant-absent";

  /** No active-strike sentiment — the {@code sentiment} dot cannot confirm (scorer:215). */
  public static final String SENTIMENT_ABSENT = "sentiment-absent";

  /** No sentiment slope — the {@code sentiment_slope} dot cannot confirm (scorer:219). */
  public static final String SENTIMENT_SLOPE_ABSENT = "sentiment-slope-absent";

  /**
   * No CE/PE OI deltas — the {@code trending_cross} dot cannot confirm (scorer:310-318). This is
   * ALSO the only genuine absence behind a null {@code callPutDeltaImbalancePct}: the producer
   * returns null from {@code imbalancePct} iff {@code max(|ceDelta|,|peDelta|) == 0}
   * (MarketOiClient:735-741), i.e. both deltas are present and exactly zero — an ordinary flat
   * chain that {@code ScalperGates.flatOiStandAside} reads as its meaningful stand-aside SENTINEL
   * (:732-740). A null imbalance therefore gets NO flag of its own: with the deltas present it is a
   * value, and with them missing this flag already covers it.
   */
  public static final String OI_DELTA_ABSENT = "oi-delta-absent";

  /** No PE−CE divergence % — the divergence gate cannot read (ScalperGates:692). */
  public static final String OI_DIVERGENCE_ABSENT = "oi-divergence-absent";

  /** No spurt magnitudes — the {@code oi_spurt} dot cannot confirm (scorer:344-352). */
  public static final String OI_SPURT_ABSENT = "oi-spurt-absent";

  /**
   * The per-root expiry calendars. Memoized as statics because {@code nse()}/{@code bse()} re-read
   * the bundled holiday CSV on every construction.
   *
   * <p>This restates {@code ScalperCalendars.forUnderlying}'s one-line root rule rather than calling
   * it: that class is package-private in {@code scalper}. {@link DotHealthCanary} (:598-615) made
   * the same call for the same reason, and this mirrors it so the two health surfaces cannot
   * disagree about which calendar a root uses.
   */
  private static final MarketCalendar NSE = MarketCalendar.nse();

  private static final MarketCalendar BSE = MarketCalendar.bse();

  /** The verdict for a row that never reached the chain fetch. */
  private static final DataHealthFlags NO_CONTEXT =
      new DataHealthFlags(false, false, false, List.of());

  /**
   * Judges one bar's gate inputs.
   *
   * @param context the gate context the diagnostic was built from; null when the block landed before
   *     the chain fetch
   * @param sessionDate the bar's own IST date — never {@code now()}, which straddles IST midnight
   *     against a bar recorded either side of it
   */
  public static DataHealthFlags of(ScalperGateContext context, LocalDate sessionDate) {
    if (context == null) {
      return NO_CONTEXT;
    }
    boolean oiSuppressed = oiSuppressed(context.underlying(), sessionDate);
    List<String> flags = new ArrayList<>(19);

    // ---- MACRO. Judged on every row, expiry or not. -------------------------------------------
    // A null Macro means every macro input is absent; flagging each individually needs no special
    // case and the resulting row says exactly that.
    ScalperGateContext.Macro macro = context.macro();
    if (macro == null || macro.advances() + macro.declines() == 0) {
      flags.add(BREADTH_ABSENT);
    }
    if (macro == null || macro.atmIv() == null) {
      flags.add(ATM_IV_ABSENT);
    }
    if (macro == null || macro.ivRank() == null) {
      flags.add(IV_RANK_ABSENT);
    }
    // EITHER leg missing blinds the pair comparison and the buyer cap, so one flag covers both.
    if (macro == null || macro.ceIvAvg6() == null || macro.peIvAvg6() == null) {
      flags.add(IV_PAIR_ABSENT);
    }
    if (macro == null || macro.ceIvSlope() == null || macro.peIvSlope() == null) {
      flags.add(IV_SLOPE_ABSENT);
    }
    if (macro == null || macro.premiumSkewPct() == null) {
      flags.add(PREMIUM_SKEW_ABSENT);
    }
    if (macro == null || macro.vixRising() == null) {
      flags.add(VIX_DIRECTION_ABSENT);
    }
    if (macro == null || macro.vixLevel() == null) {
      flags.add(VIX_LEVEL_ABSENT);
    }
    if (macro == null || macro.fiiLongPct() == null) {
      flags.add(FII_ABSENT);
    }
    // Only NULL is absence. A zero sign is a real "conflicting participant read" and a zero
    // constituent push is a real flat tape — both degrade the gate to pass, but neither is missing
    // data, and flagging them would break this class's one rule.
    if (macro == null || macro.fiiBiasSign() == null) {
      flags.add(FII_BIAS_ABSENT);
    }
    if (macro == null || macro.constituentBias() == null) {
      flags.add(CONSTITUENT_BIAS_ABSENT);
    }
    if (macro == null || macro.dowUp() == null) {
      flags.add(DOW_ABSENT);
    }

    // ---- FUTURES BASIS. On the Oi record but NOT in the suppressed group: it is price-derived and
    // MarketOiClient KEEPS it through the S24 skip, so a null basis is a real failure on any day.
    if (context.oi() == null || context.oi().futuresBasis() == null) {
      flags.add(FUTURES_BASIS_ABSENT);
    }

    // ---- CHAIN OI. Withheld WHOLESALE under S24: the skip nulls the entire chain read in one act,
    // so any per-field flag here would report a defect the design deliberately caused.
    if (!oiSuppressed) {
      flags.addAll(chainOiFlags(context.oi()));
    }
    return new DataHealthFlags(!flags.isEmpty(), true, oiSuppressed, List.copyOf(flags));
  }

  /**
   * The CHAIN-OI absences — everything the S24 skip empties. A null {@code Oi} is total absence, so
   * every flag fires. Callers must withhold this ENTIRE list under S24, never a subset.
   *
   * <p>{@code futuresBasis} is deliberately NOT here: it survives the skip and is judged separately.
   * {@code callPutDeltaImbalancePct} is deliberately NOT here either: its null is the flat-chain
   * sentinel, a value rather than an absence — see {@link #OI_DELTA_ABSENT}.
   */
  private static List<String> chainOiFlags(ScalperGateContext.Oi oi) {
    List<String> flags = new ArrayList<>(8);
    if (oiInert(oi)) {
      flags.add(OI_INERT);
    }
    if (quadrantInert(oi == null ? null : oi.futures())) {
      flags.add(FUTURES_QUADRANT_ABSENT);
    }
    if (quadrantInert(oi == null ? null : oi.underlying())) {
      flags.add(UNDERLYING_QUADRANT_ABSENT);
    }
    if (oi == null || oi.sentimentPct() == null) {
      flags.add(SENTIMENT_ABSENT);
    }
    if (oi == null || oi.sentimentSlope() == null) {
      flags.add(SENTIMENT_SLOPE_ABSENT);
    }
    if (oi == null || oi.ceOiDelta() == null || oi.peOiDelta() == null) {
      flags.add(OI_DELTA_ABSENT);
    }
    if (oi == null || oi.oiDivergencePct() == null) {
      flags.add(OI_DIVERGENCE_ABSENT);
    }
    if (oi == null || oi.spurtOiPct() == null || oi.spurtPricePct() == null) {
      flags.add(OI_SPURT_ABSENT);
    }
    return flags;
  }

  /**
   * Is THIS row's OI root on its own monthly index expiry? Mirrors
   * {@code ScalperCalendars.forUnderlying} — BSE (Thursday monthly) for a SENSEX-rooted read, NSE
   * (Tuesday monthly) for everything else. A row with no underlying falls to NSE, so a dead OI block
   * on a BSE-only expiry day still reads degraded: fail-loud is the right default for a health flag.
   */
  private static boolean oiSuppressed(String underlying, LocalDate sessionDate) {
    if (sessionDate == null) {
      return false;
    }
    MarketCalendar rootCalendar =
        underlying != null && underlying.toUpperCase(Locale.ROOT).contains("SENSEX") ? BSE : NSE;
    try {
      return rootCalendar.isMonthlyIndexExpiryDay(sessionDate);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary; never claim by-design on a guess
    }
  }

  /**
   * The inert-OI signature: the exact shape {@code MarketOiClient.oi()} returns when it skips the
   * block (NEUTRAL quadrants + null soft numerics), which is also what a total OI-read failure
   * leaves behind. Both quadrants AND all four magnitudes must be empty — on a live read even a flat
   * tape carries a {@code sentimentPct} and spurt magnitudes, so a genuine quiet bar never matches.
   * {@code futuresBasis} is excluded on purpose: it is price-derived and survives the S24 skip.
   */
  private static boolean oiInert(ScalperGateContext.Oi oi) {
    if (oi == null) {
      return true;
    }
    return quadrantInert(oi.futures())
        && quadrantInert(oi.underlying())
        && oi.sentimentPct() == null
        && oi.trendingPeMinusCePct() == null
        && oi.spurtOiPct() == null
        && oi.spurtPricePct() == null;
  }

  private static boolean quadrantInert(OiQuadrant quadrant) {
    return quadrant == null || quadrant == OiQuadrant.NEUTRAL;
  }
}
