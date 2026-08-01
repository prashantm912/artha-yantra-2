package in.arthayantra.strategysignal.paper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * M40 governor-coverage fix (E4 decision-sheet §2f): {@link RiskService#recordPyramidRiskCapBreach}
 * is the audit/alert treatment a Manas §3.4.3 pyramid-add-blocked-by-risk-cap event now gets, matching
 * every other threshold rail (daily-loss / profit-target / deployment / heat-cap) — before this method
 * existed, that ONE governor-trip type reached only the application log, never {@code risk_audit} or
 * ntfy. This is a coverage/visibility fix only: it does not change the 6% (or any other)
 * pyramid-risk-cap THRESHOLD, and pyramiding itself stays disabled by default
 * ({@code artha.manas-arora.pyramid.enabled=false}) — this call site is unreachable in production
 * until that flag is re-armed (see {@code ManasPyramidRiskCapAuditIntegrationTest} for the end-to-end
 * wiring proof).
 */
class RiskServicePyramidCapTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-02T08:00:00Z"), ZoneOffset.UTC);
  private static final String BOOK = "manas-arora";

  private record Harness(RiskService risk, RiskSettingsRepository settings, NotifierClient notifier) {}

  private static Harness harness() {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    when(notifier.configured("NTFY")).thenReturn(true);
    RiskService risk = new RiskService(settings, positions, account, margin, notifier, CLOCK, false);
    return new Harness(risk, settings, notifier);
  }

  @Test
  void breachWritesAuditRowAndPushesAlert() {
    Harness h = harness();

    h.risk()
        .recordPyramidRiskCapBreach(
            BOOK, "RELIANCE", "pyramid add for RELIANCE blocked by the 6.0% portfolio open-risk cap");

    verify(h.settings())
        .audit(
            eq(BOOK),
            eq(RiskService.PYRAMID_RISK_CAP),
            eq("TRIP"),
            eq("pyramid add for RELIANCE blocked by the 6.0% portfolio open-risk cap"));
    verify(h.notifier()).send(eq("NTFY"), any(), any());
  }

  @Test
  void secondBreachSameBookSameDayIsDeduped() {
    Harness h = harness();

    h.risk().recordPyramidRiskCapBreach(BOOK, "RELIANCE", "first breach");
    h.risk().recordPyramidRiskCapBreach(BOOK, "TCS", "second breach, same book, same IST day");

    // Matches the existing per-(book,key)-per-day dedup convention (recordTrip/recordHeatTrip) — only
    // the FIRST breach of the day for this book is audited/alerted.
    verify(h.settings(), times(1)).audit(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), eq("TRIP"), any());
    verify(h.notifier(), times(1)).send(eq("NTFY"), any(), any());
  }

  @Test
  void neverCalledMeansNeverAudited() {
    Harness h = harness();
    verify(h.settings(), never()).audit(any(), any(), any(), any());
  }
}
