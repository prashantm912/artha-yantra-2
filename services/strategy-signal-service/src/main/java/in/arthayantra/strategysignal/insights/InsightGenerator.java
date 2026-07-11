package in.arthayantra.strategysignal.insights;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One insight generator (INT design §2.2/§9.2): a PURE function from a {@link GenerationContext} to a
 * list of {@link InsightCandidate}s, with IO kept at the engine's edges. Generators are registered in
 * a catalog (the injected {@code List<InsightGenerator>} — the {@code IndicatorRegistry} precedent);
 * adding a type is additive. A generator returns {@code List.of()} when the context carries none of
 * its inputs, so the engine can run every generator per trigger without branching.
 */
public interface InsightGenerator {

  /** The type this generator emits. */
  InsightType type();

  /** Pure evaluation → zero or more candidates. */
  List<InsightCandidate> generate(GenerationContext ctx);

  /**
   * The inputs available to generators on a given trigger. Any field may be {@code null}/empty — each
   * generator reads only what it needs and returns {@code List.of()} when its inputs are absent, so
   * the engine can run every generator per trigger without branching. Built by the engine per trigger:
   * the {@code SignalEmitted} listener supplies {@code signal} + {@code trust}; each sweep supplies the
   * one bundle its generators read (I1: {@code trust} / {@code bookHeats}; I2: {@code market} /
   * {@code rejections} / {@code hygiene} / {@code expiry} / {@code staleTick} / {@code quality}).
   */
  record GenerationContext(
      SignalPriorityInputs signal,
      TrustSnapshot trust,
      List<BookHeat> bookHeats,
      MarketContext market,
      RejectionScan rejections,
      HygieneInputs hygiene,
      ExpirySnapshot expiry,
      StaleTickSnapshot staleTick,
      QualityStats quality,
      OffsetDateTime now) {

    /** Context for the on-emit signal-priority trigger. */
    public static GenerationContext forSignal(SignalPriorityInputs signal, OffsetDateTime now) {
      return new GenerationContext(signal, null, List.of(), null, null, null, null, null, null, now);
    }

    /** Context for the data-trust sweep. */
    public static GenerationContext forTrust(TrustSnapshot trust, OffsetDateTime now) {
      return new GenerationContext(null, trust, List.of(), null, null, null, null, null, null, now);
    }

    /** Context for the risk-heat sweep (RISK_HEAT + RISK_STALE_TICK share the 5-min risk sweep). */
    public static GenerationContext forRisk(
        List<BookHeat> bookHeats, StaleTickSnapshot staleTick, OffsetDateTime now) {
      return new GenerationContext(
          null, null, bookHeats == null ? List.of() : bookHeats, null, null, null, null, staleTick,
          null, now);
    }

    /** Context for the 15-min context sweep (CONTEXT_SHIFT + MARKET_STRUCTURE + REJECTION_NEARMISS). */
    public static GenerationContext forContext(
        MarketContext market, RejectionScan rejections, OffsetDateTime now) {
      return new GenerationContext(null, null, List.of(), market, rejections, null, null, null, null, now);
    }

    /** Context for the EOD sweep (REJECTION_RAIL_TREND + HYGIENE). */
    public static GenerationContext forEod(
        RejectionScan rejections, HygieneInputs hygiene, OffsetDateTime now) {
      return new GenerationContext(
          null, null, List.of(), null, rejections, hygiene, null, null, null, now);
    }

    /** Context for the T-1 expiry sweep (EXPIRY_EVENT). */
    public static GenerationContext forExpiry(ExpirySnapshot expiry, OffsetDateTime now) {
      return new GenerationContext(null, null, List.of(), null, null, null, expiry, null, null, now);
    }

    /** Context for the weekly quality-report job (QUALITY_REPORT). */
    public static GenerationContext forQuality(QualityStats quality, OffsetDateTime now) {
      return new GenerationContext(null, null, List.of(), null, null, null, null, null, quality, now);
    }
  }
}
