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
   * generator reads only what it needs. Built by the engine per trigger: the {@code SignalEmitted}
   * listener supplies {@code signal} + {@code trust}; the sweeps supply {@code trust} and/or
   * {@code bookHeats}.
   */
  record GenerationContext(
      SignalPriorityInputs signal, TrustSnapshot trust, List<BookHeat> bookHeats, OffsetDateTime now) {

    /** Context for the on-emit signal-priority trigger. */
    public static GenerationContext forSignal(SignalPriorityInputs signal, OffsetDateTime now) {
      return new GenerationContext(signal, null, List.of(), now);
    }

    /** Context for the data-trust sweep. */
    public static GenerationContext forTrust(TrustSnapshot trust, OffsetDateTime now) {
      return new GenerationContext(null, trust, List.of(), now);
    }

    /** Context for the risk-heat sweep. */
    public static GenerationContext forRisk(List<BookHeat> bookHeats, OffsetDateTime now) {
      return new GenerationContext(null, null, bookHeats == null ? List.of() : bookHeats, now);
    }
  }
}
