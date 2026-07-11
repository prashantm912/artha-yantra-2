package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * HYGIENE (INT design §2.2): on the EOD sweep, a display-only nudge when journal discipline slips —
 * auto journal entries left unrated, or closed positions with no journal link. PURE over the parsed
 * {@link HygieneInputs}. INFO severity (never pushes — collects into the digest, §2.5.5). One insight
 * per IST-day, refreshed in place.
 */
@Component
public class HygieneGenerator implements InsightGenerator {

  private final InsightProperties.Hygiene cfg;

  public HygieneGenerator(InsightProperties props) {
    this.cfg = props.hygiene();
  }

  @Override
  public InsightType type() {
    return InsightType.HYGIENE;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    HygieneInputs h = ctx.hygiene();
    if (h == null) {
      return List.of();
    }
    if (h.unratedAutoEntries() < cfg.minUnrated() && h.closedPositionsWithoutJournal() == 0) {
      return List.of();
    }
    LocalDate istDay = ctx.now().atZoneSameInstant(Ist.ZONE).toLocalDate();

    List<Evidence> evidence = new ArrayList<>();
    List<String> parts = new ArrayList<>();
    if (h.unratedAutoEntries() >= cfg.minUnrated()) {
      parts.add(h.unratedAutoEntries() + " unrated journal entr" + (h.unratedAutoEntries() == 1 ? "y" : "ies"));
      evidence.add(
          Evidence.sourced("unrated entries", String.valueOf(h.unratedAutoEntries()),
              "/api/v1/journal", java.util.Map.of("minRating", 1), ctx.now().toString()));
    }
    if (h.closedPositionsWithoutJournal() > 0) {
      parts.add(h.closedPositionsWithoutJournal() + " closed position"
          + (h.closedPositionsWithoutJournal() == 1 ? "" : "s") + " with no journal");
      evidence.add(
          Evidence.of("closed w/o journal", String.valueOf(h.closedPositionsWithoutJournal())));
    }
    String summary = String.join(", ", parts);
    return List.of(
        new InsightCandidate(
            InsightType.HYGIENE,
            Severity.INFO,
            "dataops",
            "Journal hygiene: " + summary + " in the last " + h.windowDays() + " days",
            "Over the last " + h.windowDays() + " days: " + summary + ". Rate them to keep the discipline"
                + " signal usable.",
            List.copyOf(evidence),
            null, null, DataTrust.OK, List.of(),
            "HYGIENE:" + istDay, null, null, false));
  }
}
