package in.arthayantra.strategysignal.scalper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.FiredDiagnostic;
import in.arthayantra.strategysignal.scalper.ScalperConfluenceGate.RailCheck;
import java.math.BigDecimal;

/**
 * INT §13 row 19 / FID P1-8: serializes a {@link FiredDiagnostic} into the {@code signals.fired_diagnostic}
 * JSONB. Stamped ONLY on the LIVE scalper emit path (the golden replay never reaches the confluence gate →
 * parity-safe; goldens byte-identical, no rows on backtest).
 *
 * <p><b>Shape lockstep (load-bearing):</b> this mirrors {@code SignalEngine.rejectionDiagnosticJson} —
 * {@code signal_rejections.diagnostic} — FIELD-FOR-FIELD, so the §4.2 Stage-2 fired-vs-rejected contrast
 * joins the two JSONB columns cleanly ({@code checks[]}, {@code confluence{dots[]}}, {@code context{oi,
 * macro,chart}}). A fired signal has NO blocking rail, so the block-summary scalars
 * ({@code blockingRail/operand/threshold/margin/reason}) are serialized as JSON {@code null} (key present,
 * value null) to keep the top-level shape identical; the vetoed {@code wouldBeLeg} becomes the actually
 * traded {@code firedLeg}. If the rejection shape ever changes, change this in lockstep.
 */
public final class FiredDiagnosticJson {

  private FiredDiagnosticJson() {}

  /** The fired-side diagnostic JSON — mirrors the rejection diagnostic shape (block scalars null). */
  public static String write(ObjectMapper objectMapper, FiredDiagnostic d) {
    ObjectNode root = objectMapper.createObjectNode();
    // A fired entry has no blocking rail — these top-level block scalars stay null to keep the shape
    // identical to signal_rejections.diagnostic (so the Stage-2 contrast unions/joins the two cleanly).
    root.put("blockingRail", (String) null);
    root.put("side", d.side() == null ? null : d.side().name());
    root.put("operand", (BigDecimal) null);
    root.put("threshold", (BigDecimal) null);
    root.put("margin", (BigDecimal) null);
    root.put("reason", (String) null);
    root.put("compositeScore", d.compositeScore());
    root.put("compositeThreshold", d.compositeThreshold());
    // The actually-traded leg (the fired-side mirror of the rejection's vetoed wouldBeLeg) — present for a
    // directional entry once the StrikePicker resolved; null for the neutral straddle (legs ride the
    // Decision) and any pre-pick path.
    if (d.pick() != null) {
      ObjectNode leg = root.putObject("firedLeg");
      leg.put("tradingsymbol", d.pick().candidate().tradingsymbol());
      leg.put("strike", d.pick().candidate().strike());
      leg.put("optionType", d.side() == null ? null : d.side().name());
      leg.put("expiry", d.expiry() == null ? null : d.expiry().toString());
      leg.put("entryLtp", d.pick().candidate().ltp());
      leg.put("delta", d.pick().delta());
      leg.put("structuralStop", d.structuralStop());
    }
    ArrayNode checks = root.putArray("checks");
    for (RailCheck c : d.checks()) {
      ObjectNode n = checks.addObject();
      n.put("rail", c.rail());
      n.put("pass", c.pass());
      n.put("operand", c.operand());
      n.put("threshold", c.threshold());
      n.put("margin", c.margin());
      n.put("reason", c.reason());
      n.put("failPolicy", c.policy().name()); // P2: the rail's declared missing-data polarity
    }
    if (d.confluence() != null) {
      ConnectTheDotsScorer.Confluence conf = d.confluence();
      ObjectNode c = root.putObject("confluence");
      c.put("aggregate", conf.aggregate());
      c.put("threshold", d.compositeThreshold());
      c.put("bullish", conf.bullish());
      c.put("bearish", conf.bearish());
      c.put("vwapAligned", conf.vwapAligned());
      c.put("biasAligned", conf.biasAligned());
      c.put("standAside", conf.standAside());
      ArrayNode dots = c.putArray("dots");
      for (ConnectTheDotsScorer.DotScore ds : conf.dots()) {
        ObjectNode n = dots.addObject();
        n.put("dot", ds.dot());
        n.put("weight", ds.weight());
        n.put("supports", ds.supports());
        // F5 U4a — in lockstep with SignalEngine.rejectionDiagnosticJson: `absent` marks a dot whose
        // INPUT was missing (withheld from BOTH num and den). It qualifies `supports`, which also
        // reads false for a withheld dot, so the fired-vs-rejected contrast can now separate "no
        // data" from "data said no" without inferring it from the weight sum.
        n.put("absent", ds.absent());
        n.put("reason", ds.reason());
      }
    }
    if (d.context() != null) {
      ScalperGateContext ctx = d.context();
      ObjectNode c = root.putObject("context");
      c.put("underlying", ctx.underlying());
      c.put("signalIndex", ctx.signalIndex());
      ScalperGateContext.Chart ch = ctx.chart();
      ObjectNode chart = c.putObject("chart");
      chart.put("close", ch.close());
      chart.put("vwap", ch.vwap());
      chart.put("vwma20", ch.vwma20());
      chart.put("psar", ch.psar());
      chart.put("supertrendDir", ch.supertrendDir());
      chart.put("rsi14", ch.rsi14());
      chart.put("volume", ch.volume());
      chart.put("rsi5m", ch.rsi5m());
      chart.put("rsiDaily", ch.rsiDaily());
      ScalperGateContext.Oi oi = ctx.oi();
      ObjectNode on = c.putObject("oi");
      on.put("underlyingQuadrant", String.valueOf(oi.underlying()));
      on.put("futuresQuadrant", String.valueOf(oi.futures()));
      on.put("sentimentPct", oi.sentimentPct());
      on.put("trendingPeMinusCePct", oi.trendingPeMinusCePct());
      on.put("futuresBasis", oi.futuresBasis());
      on.put("ceOiDelta", oi.ceOiDelta());
      on.put("peOiDelta", oi.peOiDelta());
      on.put("callPutDeltaImbalancePct", oi.callPutDeltaImbalancePct());
      on.put("crossedThisWindow", oi.crossedThisWindow());
      on.put("gapWidening", oi.gapWidening());
      on.put("sentimentSlope", oi.sentimentSlope());
      on.put("spurtOiPct", oi.spurtOiPct());
      on.put("spurtPricePct", oi.spurtPricePct());
      on.put("oiDivergencePct", oi.oiDivergencePct());
      ScalperGateContext.Macro m = ctx.macro();
      ObjectNode mn = c.putObject("macro");
      mn.put("atmIv", m.atmIv());
      mn.put("ivRank", m.ivRank());
      mn.put("vixLevel", m.vixLevel());
      mn.put("vixRising", m.vixRising());
      mn.put("advances", m.advances());
      mn.put("declines", m.declines());
      mn.put("fiiLongPct", m.fiiLongPct());
      mn.put("ceIvAvg6", m.ceIvAvg6());
      mn.put("peIvAvg6", m.peIvAvg6());
      mn.put("constituentBias", m.constituentBias());
      mn.put("ceIvSlope", m.ceIvSlope());
      mn.put("peIvSlope", m.peIvSlope());
      mn.put("premiumSkewPct", m.premiumSkewPct());
      mn.put("dowUp", m.dowUp());
      mn.put("fiiBiasSign", m.fiiBiasSign());
    }
    return root.toString();
  }
}
