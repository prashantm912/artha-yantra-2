package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * MEASUREMENT ONLY — the per-bar counterfactual for the active-strike sentiment OPERAND. Nothing on
 * the live path reads this record: it is built inside the two diagnostic serializers, after every
 * decision has been made, and recorded beside that decision. Which bars pass the gate is unchanged.
 *
 * <p><b>The question it answers.</b> Both live consumers of the sentiment number are pure SIGN tests
 * with no magnitude floor — the {@code sentiment} dot ({@link ConnectTheDotsScorer#sideSigned}) and
 * the {@code oi-slope-agree} rail ({@link ScalperGates#oiSlopeAgree}) — and both read
 * {@link Oi#sentimentPct()}, which is the ΔOI <b>FLOW</b>: {@code 100·Σ(peΔOI − ceΔOI)/Σ OI} over the
 * active strikes. Measured live on 2026-08-03 over 55 3-minute buckets per pair, that operand flips
 * sign 19–21× per session, sits under |0.5| in most buckets, and is <b>exactly 0.00</b> in ~34% of
 * SENSEX buckets — because exchange OI dissemination is coarser than the 2-minute snapshot cadence,
 * so whenever the newest bucket lands on a no-dissemination tick the scalar is 0 and {@code
 * sideSigned} fails it for CE and PE simultaneously. The LEVEL-based sibling
 * ({@link Oi#sentimentLevelPct()}, {@code 100·(ΣputOI − ΣcallOI)/ΣputOI}, OI stock rather than flow)
 * flipped 0–2× over the same window. This records, per bar, what each of the two sign tests WOULD
 * have said on the level operand — the evidence a later decision to switch would need.
 *
 * <p><b>Single-sourced, never re-derived.</b> Each verdict runs the LIVE predicate with only the
 * operand substituted ({@link #withSentiment}), so a change to either rule moves the counterfactual
 * with it. Re-implementing the sign tests here would let the measurement drift away from the thing
 * it claims to measure.
 *
 * <p><b>Missing level ⇒ no verdict, never a guess.</b> A null {@code sentimentLevelPct} (market-data
 * omits the key; the S24 monthly-expiry suppression zeroes the whole OI block; no side resolved on
 * the neutral-straddle path) yields {@code null} for BOTH verdicts. It deliberately does NOT
 * reuse the live fail-closed {@code false}: "the counterfactual could not be evaluated" and "the
 * counterfactual says no" are different facts, and collapsing them would bias the measurement
 * toward the incumbent exactly on the bars where the data is thin.
 *
 * <p><b>…and the null says WHY ({@link Reason}).</b> Those distinct causes all used to serialize as
 * the same four nulls. On 2026-08-25 — an NSE monthly index expiry — every one of 1,478 rejection
 * rows recorded four nulls after 14 populated sessions, and two experienced readers independently
 * built a "self-contradicting row" theory from it, one filing it as a live regression. The behaviour
 * was CORRECT; the record simply could not say so. {@link #reason()} now discriminates, so the
 * by-design case is one {@code WHERE} clause away from the failure cases. The verdicts themselves are
 * unchanged — this adds a discriminator, it does not fill anything in. Filling them in would be
 * worse than useless: on a monthly expiry the expiring series' writers are unwinding, so a verdict
 * computed off that chain would be measurement garbage wearing a number's clothes.
 *
 * <p><b>An ABSENT {@code reason} key means UNKNOWN/LEGACY.</b> Every {@code signal_rejections} /
 * {@code signals} row written through 2026-08-25 predates the field, and its cause is genuinely
 * unrecoverable from the row alone. Absence is NOT a fifth cause and must never be read as one: the
 * honest treatment is to EXCLUDE such rows from a cause breakdown rather than bucket them, and to
 * say how many were excluded. {@link Reason} deliberately declares no {@code UNKNOWN} constant, so
 * nothing can ever WRITE the legacy state and let a new row impersonate an old one — the absence is
 * the only marker, and it cannot be forged.
 */
public record SentimentLevelShadow(
    BigDecimal flowPct,
    BigDecimal levelPct,
    Boolean sentimentDotWouldSupport,
    Boolean oiSlopeAgreeWouldPass,
    // Why this row's verdicts are what they are — NEVER null on a row written by this class. An
    // ABSENT `reason` key means UNKNOWN/LEGACY; see the class javadoc above for the full contract.
    Reason reason) {

  /**
   * Fail LOUD on a null {@code reason}. The class javadoc above says the legacy state "cannot be
   * forged"; until this constructor existed that was PROSE, not code — the canonical constructor is
   * public and {@link #appendTo} writes {@code "reason": null}, which under {@code ->>'reason'} is
   * SQL NULL and therefore indistinguishable from an absent key, i.e. from a genuine legacy row.
   * No production path can reach this (all four sites in {@link #of} pass a constant), so this is a
   * guard against a future programming error, never a data path. Review finding, 2026-08-25.
   */
  public SentimentLevelShadow {
    Objects.requireNonNull(reason, "reason must never be null — an ABSENT key is the legacy marker");
  }

  /**
   * Why the counterfactual is, or is not, computable on this bar — the discriminator the four nulls
   * lacked. Serialized by {@link #appendTo} as its {@code name()}.
   *
   * <p>Ordering in {@link #of} is by ROOT CAUSE, not by which check happens to be cheapest: a
   * suppressed snapshot also has a null level, and reporting it as {@link #LEVEL_UNAVAILABLE} would
   * re-create exactly the ambiguity this enum exists to remove.
   */
  public enum Reason {

    /** Both verdicts computed — the level operand was present and a side was resolved. */
    COMPUTED,

    /**
     * No OI snapshot reached this measurement at all: the bar was blocked before {@code
     * MarketOiClient.context()} resolved, so neither operand was ever read. Ordinary and frequent:
     * 4,048 of the 18,080 live rejection ROWS carrying this block (22.4%, measured 2026-08-25 over
     * 2026-08-04..25). ROWS, NOT INDEPENDENT BARS — this book's slugs fan out, so one bar can
     * contribute many rows; the share is honest as a share OF ROWS and must not be promoted into
     * an observation count. The argument it supports does not depend on independence: the common
     * case and the by-design case wrote the identical block, which is why a bare "all four null"
     * carried no information.
     */
    NO_OI_CONTEXT,

    /**
     * BY DESIGN, and never a defect: the day is a monthly index expiry, so {@code MarketOiClient.oi}
     * took its S24 branch and returned inert defaults without calling any OI endpoint. The chain OI
     * on such a day is corrupted by expiring writers, so there is no honest level to measure — the
     * absence of a verdict IS the correct outcome.
     */
    MONTHLY_EXPIRY_SUPPRESSED,

    /**
     * An OI snapshot exists and was NOT suppressed, yet carries no {@code sentimentLevelPct}. This
     * is the one that deserves a look: market-data omitted the key (an older deploy) or the
     * active-strikes read failed. ⚠️ Those two are NOT separated here — the {@code get} seam maps
     * both to the same inert default — so this constant means "unexpectedly absent", not
     * "endpoint down".
     */
    LEVEL_UNAVAILABLE,

    /**
     * The level operand is present, but no side was resolved (the #11 neutral-straddle path), so a
     * side-dependent verdict is unknowable. Both operands are still recorded on the row.
     */
    SIDE_UNRESOLVED
  }

  /** No context reached the gate — both operands and both verdicts unknown. */
  public static final SentimentLevelShadow EMPTY =
      new SentimentLevelShadow(null, null, null, null, Reason.NO_OI_CONTEXT);

  /**
   * The counterfactual for {@code side} on this bar's OI snapshot. Pure and total: any missing input
   * degrades to a null verdict, never an exception.
   */
  public static SentimentLevelShadow of(Oi oi, OptionType side) {
    if (oi == null) {
      return EMPTY;
    }
    BigDecimal flow = oi.sentimentPct();
    BigDecimal level = oi.sentimentLevelPct();
    if (level == null) {
      // Root cause first: a suppressed snapshot ALSO has a null level, and calling that
      // LEVEL_UNAVAILABLE would restore the ambiguity. The flag is provenance recorded at
      // construction, so it cannot disagree with what the producer actually did.
      return new SentimentLevelShadow(
          flow, null, null, null,
          oi.monthlyExpirySuppressed() ? Reason.MONTHLY_EXPIRY_SUPPRESSED : Reason.LEVEL_UNAVAILABLE);
    }
    if (side == null) {
      return new SentimentLevelShadow(flow, level, null, null, Reason.SIDE_UNRESOLVED);
    }
    Oi substituted = withSentiment(oi, level);
    return new SentimentLevelShadow(
        flow,
        level,
        ConnectTheDotsScorer.sideSigned(level, side == OptionType.CE),
        ScalperGates.oiSlopeAgree(substituted, side).pass(),
        Reason.COMPUTED);
  }

  /**
   * The bar's OI snapshot with the FLOW operand REPLACED by the LEVEL operand — the substitution that
   * turns any live sentiment rule into its counterfactual. Null when there is no level to substitute,
   * so a caller can treat null as "no counterfactual is computable".
   *
   * <p>Derived purely from the already-fetched immutable snapshot: it copies one record and re-points
   * one field. There is NO second fetch, so a counterfactual built on it is reproducible and is
   * guaranteed to have seen exactly what the live decision saw.
   */
  public static Oi withLevelAsFlow(Oi oi) {
    if (oi == null || oi.sentimentLevelPct() == null) {
      return null;
    }
    return withSentiment(oi, oi.sentimentLevelPct());
  }

  /**
   * The whole per-bar context with the OI operand substituted — what a counterfactual re-scoring of
   * the confluence must run against. Null when no level is available. Chart and macro ride through
   * unchanged; only the one operand under test moves.
   */
  public static ScalperGateContext withLevelAsFlow(ScalperGateContext ctx) {
    if (ctx == null) {
      return null;
    }
    Oi substituted = withLevelAsFlow(ctx.oi());
    return substituted == null
        ? null
        : new ScalperGateContext(
            ctx.underlying(), ctx.signalIndex(), ctx.istTime(), ctx.chart(), substituted,
            ctx.macro());
  }

  /**
   * A copy of {@code oi} with the FLOW sentiment replaced by {@code sentiment} — the substitution
   * that turns a live rule into its counterfactual. Every other field (the slope especially, which
   * {@code oi-slope-agree} also reads) is carried through untouched, so the verdict isolates the one
   * operand under test. The copy is local to this method and never escapes onto the live path.
   */
  private static Oi withSentiment(Oi oi, BigDecimal sentiment) {
    return new Oi(
        oi.underlying(), oi.futures(), sentiment, oi.trendingPeMinusCePct(), oi.futuresBasis(),
        oi.ceOiDelta(), oi.peOiDelta(), oi.callPutDeltaImbalancePct(), oi.crossedThisWindow(),
        oi.gapWidening(), oi.sentimentSlope(), oi.spurtOiPct(), oi.spurtPricePct(),
        oi.oiDivergencePct(), oi.sentimentLevelPct(), oi.monthlyExpirySuppressed());
  }

  /**
   * Writes the {@code sentimentLevelShadow} object onto a diagnostic root. Shared by BOTH diagnostic
   * serializers ({@code FiredDiagnosticJson} and {@code SignalEngine.rejectionDiagnosticJson}) so the
   * fired/rejected shape lockstep holds by construction rather than by matching two hand-written
   * blocks. The key is ALWAYS present — an unreachable context records four nulls, which is itself
   * the finding "this bar never got far enough to read the operand".
   *
   * <p>{@code reason} is the only NON-nullable member of the block, which is what makes it usable as
   * a straight {@code GROUP BY diagnostic->'sentimentLevelShadow'->>'reason'} over the table. A SQL
   * NULL there is a pre-2026-08-26 row, not a fifth cause — see {@link #reason()}.
   */
  public void appendTo(ObjectNode root) {
    ObjectNode n = root.putObject("sentimentLevelShadow");
    // Both operands ride here rather than being joined out of context.oi, so one JSONB path carries
    // the whole comparison; context.oi stays byte-identical to what it was before this change.
    n.put("flowPct", flowPct);
    n.put("levelPct", levelPct);
    n.put("dotWouldSupport", sentimentDotWouldSupport);
    n.put("slopeGateWouldPass", oiSlopeAgreeWouldPass);
    n.put("reason", reason == null ? null : reason.name());
  }
}
