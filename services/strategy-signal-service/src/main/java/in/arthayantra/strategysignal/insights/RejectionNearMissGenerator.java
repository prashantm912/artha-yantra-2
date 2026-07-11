package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * REJECTION_NEARMISS (INT design §4.2): on the 15-min sweep, rank the session's rejections closest to
 * firing — "what almost fired" is the owner's cheapest tuning signal (the automated form of the
 * 2026-07-02 volume-floor forensics, done by hand then). PURE over the parsed {@link RejectionScan}
 * near-miss list ({@link RejectionReader} already computed each closeness = |margin| / |threshold|
 * for numeric rails only). One insight per IST-day, refreshed in place each sweep with the current
 * top-N — a single evolving "near-miss board", not a per-rejection spray.
 */
@Component
public class RejectionNearMissGenerator implements InsightGenerator {

  private final InsightProperties.Rejection cfg;

  public RejectionNearMissGenerator(InsightProperties props) {
    this.cfg = props.rejection();
  }

  @Override
  public InsightType type() {
    return InsightType.REJECTION_NEARMISS;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    RejectionScan scan = ctx.rejections();
    if (scan == null || scan.nearMisses() == null || scan.nearMisses().isEmpty()) {
      return List.of();
    }
    List<RejectionScan.NearMiss> top =
        scan.nearMisses().stream()
            .filter(n -> n.closeness() != null && n.closeness().compareTo(cfg.nearMissMaxCloseness()) <= 0)
            .limit(cfg.nearMissTopN())
            .toList();
    if (top.isEmpty()) {
      return List.of();
    }
    LocalDate istDay = ctx.now().atZoneSameInstant(Ist.ZONE).toLocalDate();

    List<Evidence> evidence = new ArrayList<>();
    for (RejectionScan.NearMiss n : top) {
      evidence.add(
          Evidence.sourced(
              n.tradingsymbol() + " (" + n.blockingRail() + ")",
              "within " + pct(n.closeness()) + " of firing — operand " + plain(n.blockingOperand())
                  + " vs threshold " + plain(n.blockingThreshold()),
              "/api/v1/signal-rejections",
              java.util.Map.of("tradingsymbol", n.tradingsymbol()),
              ctx.now().toString()));
    }
    RejectionScan.NearMiss closest = top.get(0);
    String title =
        top.size() + " rejection" + (top.size() == 1 ? "" : "s") + " within "
            + pct(cfg.nearMissMaxCloseness()) + " of firing (closest " + closest.tradingsymbol()
            + " on " + closest.blockingRail() + ")";
    String explanation =
        top.size() + " rejection" + (top.size() == 1 ? "" : "s") + " came within "
            + pct(cfg.nearMissMaxCloseness()) + " of firing today; the closest was "
            + closest.tradingsymbol() + " blocked by " + closest.blockingRail() + " ("
            + pct(closest.closeness()) + " short). These are the cheapest gate-tuning candidates.";

    return List.of(
        new InsightCandidate(
            InsightType.REJECTION_NEARMISS,
            Severity.NOTICE,
            "market",
            title,
            explanation,
            List.copyOf(evidence),
            null, null, DataTrust.OK, List.of(),
            "REJECTION_NEARMISS:" + istDay, cfg.nearMissCooldownMinutes(), null, false));
  }

  private static String pct(java.math.BigDecimal fraction) {
    return fraction == null
        ? "n/a"
        : fraction.multiply(java.math.BigDecimal.valueOf(100))
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + "%";
  }

  private static String plain(java.math.BigDecimal v) {
    return v == null ? "n/a" : v.stripTrailingZeros().toPlainString();
  }
}
