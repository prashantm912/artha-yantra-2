package in.arthayantra.strategysignal.insights;

import in.arthayantra.common.web.time.Ist;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EXPIRY_EVENT (INT design §2.2): the T-1 sweep — open derivative positions expiring within the
 * window, plus holiday proximity around the expiry (the in-app complement to the existing T-1
 * roll-or-close push). PURE over the parsed {@link ExpirySnapshot} ({@link PortfolioReader} joined
 * each open position to its opening signal's {@code scalper_detail.expiry} and resolved the next
 * holiday from the market calendar). WARN — an expiring leg needs a deliberate roll-or-close. One
 * summary insight per IST-day.
 */
@Component
public class ExpiryEventGenerator implements InsightGenerator {

  @Override
  public InsightType type() {
    return InsightType.EXPIRY_EVENT;
  }

  @Override
  public List<InsightCandidate> generate(GenerationContext ctx) {
    ExpirySnapshot snap = ctx.expiry();
    if (snap == null || snap.expiring() == null || snap.expiring().isEmpty()) {
      return List.of();
    }
    List<ExpirySnapshot.ExpiringPosition> expiring = snap.expiring();
    LocalDate istDay = snap.istToday();

    List<Evidence> evidence = new ArrayList<>();
    for (ExpirySnapshot.ExpiringPosition p : expiring) {
      evidence.add(
          Evidence.of(
              p.exchange() + ":" + p.tradingsymbol(),
              p.side() + " " + p.qty() + " — expires " + p.expiry() + " (in " + p.daysToExpiry()
                  + " day" + (p.daysToExpiry() == 1 ? "" : "s") + ")"));
    }
    String holidayNote = "";
    if (snap.nextHoliday() != null) {
      long daysToHoliday = ChronoUnit.DAYS.between(istDay, snap.nextHoliday());
      if (daysToHoliday >= 0 && daysToHoliday <= 3) {
        String name = snap.nextHolidayName() == null ? "market holiday" : snap.nextHolidayName();
        holidayNote = " Note: " + name + " on " + snap.nextHoliday() + " (in " + daysToHoliday
            + " day" + (daysToHoliday == 1 ? "" : "s") + ") shifts the trading week.";
        evidence.add(Evidence.of("next holiday", name + " " + snap.nextHoliday()));
      }
    }
    int n = expiring.size();
    long maxDays = expiring.stream().mapToLong(ExpirySnapshot.ExpiringPosition::daysToExpiry).max().orElse(1);
    String title = n + " open paper leg" + (n == 1 ? " expires" : "s expire") + " soon";
    String explanation =
        n + " open derivative paper position" + (n == 1 ? " expires" : "s expire") + " within "
            + maxDays + " day" + (maxDays == 1 ? "" : "s") + " — roll or close before settlement."
            + holidayNote;

    return List.of(
        new InsightCandidate(
            InsightType.EXPIRY_EVENT,
            Severity.WARN,
            "market",
            title,
            explanation,
            List.copyOf(evidence),
            null, null, DataTrust.OK, List.of(),
            "EXPIRY_EVENT:" + istDay, null,
            istDay.plusDays(2).atStartOfDay(Ist.ZONE).toOffsetDateTime(), false));
  }
}
