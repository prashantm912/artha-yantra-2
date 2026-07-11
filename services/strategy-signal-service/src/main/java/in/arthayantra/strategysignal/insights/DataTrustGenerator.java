package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * DATA_TRUST (INT design §2.2/§7.3): one insight per data family that is DEGRADED or BLOCKED — the
 * "silent hole" killer at the CONSUMPTION layer (complements P1-Phase-1's ingest canary at the
 * ingestion layer). WARN for DEGRADED, CRITICAL for a BLOCKED load-bearing family. Deduped per
 * (family, IST-day) so a family that stays degraded all session yields one row, refreshed in place.
 */
@Component
public class DataTrustGenerator implements InsightGenerator {

  @Override
  public InsightType type() {
    return InsightType.DATA_TRUST;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    TrustSnapshot trust = ctx.trust();
    if (trust == null) {
      return List.of();
    }
    LocalDate istDay = ctx.now().atZoneSameInstant(Ist.ZONE).toLocalDate();
    List<InsightCandidate> out = new ArrayList<>();
    for (TrustSnapshot.FamilyTrust f : trust.families()) {
      if (f.state() == DataTrust.OK) {
        continue;
      }
      boolean blocked = f.state() == DataTrust.BLOCKED;
      String reasons = f.reasons() == null || f.reasons().isEmpty() ? "no detail" : String.join("; ", f.reasons());
      List<Evidence> ev = new ArrayList<>();
      ev.add(
          Evidence.sourced(
              f.family() + " trust",
              f.state().name() + " — " + reasons,
              endpointFor(f.family()),
              f.asOf() == null ? trust.asOf() : f.asOf()));
      out.add(
          new InsightCandidate(
              InsightType.DATA_TRUST,
              blocked ? Severity.CRITICAL : Severity.WARN,
              "dataops",
              f.family() + " data " + f.state().name().toLowerCase(),
              "The " + f.family() + " data family is " + f.state().name() + ": " + reasons
                  + ". Advice depending on it is " + (blocked ? "withheld" : "caveated") + " until it recovers.",
              ev,
              null,
              null,
              f.state(),
              f.reasons(),
              "DATA_TRUST:" + f.family() + ":" + istDay,
              null,
              null,
              false));
    }
    return out;
  }

  /** The health rail a family's trust reads from (for the navigable evidence source). */
  private static String endpointFor(String family) {
    return "live-capture".equals(family)
        ? "/api/v1/market/health/data"
        : "/api/v1/market/health/ingest";
  }
}
