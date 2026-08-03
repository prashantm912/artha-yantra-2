package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.black76.Black76.OptionType;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.math.BigDecimal;

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
 */
public record SentimentLevelShadow(
    BigDecimal flowPct,
    BigDecimal levelPct,
    Boolean sentimentDotWouldSupport,
    Boolean oiSlopeAgreeWouldPass) {

  /** No context reached the gate — both operands and both verdicts unknown. */
  public static final SentimentLevelShadow EMPTY =
      new SentimentLevelShadow(null, null, null, null);

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
    if (level == null || side == null) {
      return new SentimentLevelShadow(flow, level, null, null);
    }
    Oi substituted = withSentiment(oi, level);
    return new SentimentLevelShadow(
        flow,
        level,
        ConnectTheDotsScorer.sideSigned(level, side == OptionType.CE),
        ScalperGates.oiSlopeAgree(substituted, side).pass());
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
        oi.oiDivergencePct(), oi.sentimentLevelPct());
  }

  /**
   * Writes the {@code sentimentLevelShadow} object onto a diagnostic root. Shared by BOTH diagnostic
   * serializers ({@code FiredDiagnosticJson} and {@code SignalEngine.rejectionDiagnosticJson}) so the
   * fired/rejected shape lockstep holds by construction rather than by matching two hand-written
   * blocks. The key is ALWAYS present — an unreachable context records four nulls, which is itself
   * the finding "this bar never got far enough to read the operand".
   */
  public void appendTo(ObjectNode root) {
    ObjectNode n = root.putObject("sentimentLevelShadow");
    // Both operands ride here rather than being joined out of context.oi, so one JSONB path carries
    // the whole comparison; context.oi stays byte-identical to what it was before this change.
    n.put("flowPct", flowPct);
    n.put("levelPct", levelPct);
    n.put("dotWouldSupport", sentimentDotWouldSupport);
    n.put("slopeGateWouldPass", oiSlopeAgreeWouldPass);
  }
}
