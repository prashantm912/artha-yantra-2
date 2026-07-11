package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * QUALITY_REPORT (INT design §10.2): the weekly usefulness/consistency report AS an insight whose
 * evidence payload IS the report — act rate, per-type dismiss rates, and the suppression audit. PURE
 * over the parsed {@link QualityStats} ({@link InsightRepository} aggregated the trailing window). The
 * report PROPOSES: a type whose dismiss rate exceeds the config threshold is flagged as a retirement
 * candidate; nothing self-tunes. One insight per week (dedupe on the IST week-start), refreshed if the
 * week's stats are recomputed.
 */
@Component
public class QualityReportGenerator implements InsightGenerator {

  private final InsightProperties.Quality cfg;

  public QualityReportGenerator(InsightProperties props) {
    this.cfg = props.quality();
  }

  @Override
  public InsightType type() {
    return InsightType.QUALITY_REPORT;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    QualityStats q = ctx.quality();
    if (q == null) {
      return List.of();
    }
    LocalDate istDay = ctx.now().atZoneSameInstant(Ist.ZONE).toLocalDate();
    LocalDate weekStart = istDay.minusDays((istDay.getDayOfWeek().getValue() + 6) % 7); // Monday anchor

    List<Evidence> evidence = new ArrayList<>();
    evidence.add(
        Evidence.of(
            "A-band act rate",
            pct(q.aBandActRate()) + " of " + q.aBandGenerated() + " (target " + pct(cfg.actRateTarget()) + ")"));
    evidence.add(Evidence.of("suppressed insights", String.valueOf(q.suppressedTotal())));
    for (QualityStats.KeyCount k : q.topSuppressedKeys()) {
      evidence.add(Evidence.of("suppressed: " + k.dedupeKey(), k.count() + "×"));
    }
    List<String> retirementCandidates = new ArrayList<>();
    for (QualityStats.TypeStat t : q.perType()) {
      String verdict = "";
      if (t.dismissRate() != null && t.dismissRate().compareTo(cfg.dismissRateThreshold()) > 0) {
        verdict = " — DISMISS-RATE HIGH (retirement candidate)";
        retirementCandidates.add(t.type());
      }
      evidence.add(
          Evidence.of(
              t.type(),
              t.generated() + " generated, " + t.usefulVotes() + "👍/" + t.notUsefulVotes() + "👎"
                  + (t.dismissRate() == null ? "" : ", dismiss " + pct(t.dismissRate())) + verdict));
    }

    boolean actRateBelow =
        q.aBandGenerated() > 0 && q.aBandActRate().compareTo(cfg.actRateTarget()) < 0;
    String title =
        "Insight quality report — week of " + weekStart + " (" + q.aBandGenerated() + " A-band, "
            + q.suppressedTotal() + " suppressed)";
    StringBuilder explanation = new StringBuilder();
    explanation
        .append("Over the last ").append(q.windowDays()).append(" days: A-band act rate ")
        .append(pct(q.aBandActRate())).append(" (target ").append(pct(cfg.actRateTarget())).append("), ")
        .append(q.suppressedTotal()).append(" insight(s) suppressed by caution.");
    if (!retirementCandidates.isEmpty()) {
      explanation.append(" Retirement candidates (dismiss-rate > ").append(pct(cfg.dismissRateThreshold()))
          .append("): ").append(String.join(", ", retirementCandidates)).append(".");
    }
    explanation.append(" The report proposes — it never self-tunes.");

    return List.of(
        new InsightCandidate(
            InsightType.QUALITY_REPORT,
            actRateBelow || !retirementCandidates.isEmpty() ? Severity.WARN : Severity.NOTICE,
            "dataops",
            title,
            explanation.toString(),
            List.copyOf(evidence),
            null, null, DataTrust.OK, List.of(),
            "QUALITY_REPORT:" + weekStart, null, null, false));
  }

  private static String pct(BigDecimal fraction) {
    return fraction == null
        ? "n/a"
        : fraction.multiply(BigDecimal.valueOf(100))
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + "%";
  }
}
