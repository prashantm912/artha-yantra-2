package in.arthayantra.strategysignal.insights;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SIGNAL_PRIORITY (INT design §2.2/§3.2): ranks an ACTIVE signal by the additive-components ×
 * trust-cap model, display-only. Severity follows the band→push mapping (§2.5.5: A→WARN, B→NOTICE,
 * C/D→INFO) so I3/I4 delivery floors fall out for free; in I1 nothing pushes. A trust-BLOCKED signal
 * is emitted WITHOUT a score (never rank on bad data, §7.4), naming the blockers.
 */
@Component
public class SignalPriorityGenerator implements InsightGenerator {

  private final PriorityModel model;
  private final InsightProperties props;

  public SignalPriorityGenerator(InsightProperties props) {
    this.props = props;
    this.model = new PriorityModel(props.priority());
  }

  @Override
  public InsightType type() {
    return InsightType.SIGNAL_PRIORITY;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    SignalPriorityInputs in = ctx.signal();
    if (in == null) {
      return List.of();
    }
    String scope = "signal:" + in.signalId();
    String dedupeKey = "SIGNAL_PRIORITY:" + scope;
    OffsetDateTime expiresAt =
        ctx.now().plusMinutes(props.priority().ttlMinutesFor(in.family()));
    PriorityModel.Result r = model.evaluate(in);

    if (r.blocked()) {
      String reasons = in.trustReasons() == null || in.trustReasons().isEmpty()
          ? "load-bearing input unavailable"
          : String.join("; ", in.trustReasons());
      List<Evidence> ev = new ArrayList<>();
      ev.add(Evidence.of("data trust", "BLOCKED — " + reasons));
      ev.add(Evidence.sourced("signal", in.tradingsymbol() + " " + in.side(), "/api/v1/signals/" + in.signalId(), in.asOf()));
      return List.of(
          new InsightCandidate(
              InsightType.SIGNAL_PRIORITY,
              Severity.WARN,
              scope,
              in.tradingsymbol() + " " + in.side() + " — not scored (data BLOCKED)",
              "Priority not computed for " + in.tradingsymbol() + ": " + reasons
                  + ". Advice is withheld until the data recovers (never ranked on bad data).",
              ev,
              null,
              null,
              DataTrust.BLOCKED,
              in.trustReasons(),
              dedupeKey,
              null,
              expiresAt,
              false));
    }

    Severity severity =
        switch (r.band()) {
          case "A" -> Severity.WARN;
          case "B" -> Severity.NOTICE;
          default -> Severity.INFO;
        };
    List<Evidence> evidence = new ArrayList<>();
    for (PriorityDetail.Component c : r.detail().components()) {
      evidence.addAll(c.evidence());
    }
    String title = in.tradingsymbol() + " " + in.side() + " — priority " + r.score().intValue() + " (" + r.band() + ")";
    StringBuilder explanation = new StringBuilder();
    explanation
        .append("Priority ")
        .append(r.score().stripTrailingZeros().toPlainString())
        .append(" (band ")
        .append(r.band())
        .append("): ");
    List<String> parts = new ArrayList<>();
    for (PriorityDetail.Component c : r.detail().components()) {
      parts.add(c.key() + " " + c.points().stripTrailingZeros().toPlainString() + " pts");
    }
    explanation.append(String.join(", ", parts)).append(".");
    if (in.trust() == DataTrust.DEGRADED) {
      explanation.append(" Data DEGRADED — score capped at B-band ceiling.");
    }
    return List.of(
        new InsightCandidate(
            InsightType.SIGNAL_PRIORITY,
            severity,
            scope,
            title,
            explanation.toString(),
            List.copyOf(evidence),
            r.score(),
            r.detail(),
            in.trust(),
            in.trustReasons(),
            dedupeKey,
            null,
            expiresAt,
            false));
  }
}
