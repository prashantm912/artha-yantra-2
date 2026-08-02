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
 * Add-path observability fix (E4 decision-sheet §2f) plus its M40 (2026-08-02) fresh-entry extension
 * — see {@code docs/signal-analysis/2026-08-02-m40-fresh-entry-risk-cap-gap.md} for the gap: {@link
 * RiskService#recordPyramidRiskCapBreach} is the audit/alert treatment BOTH a Manas §3.4.3
 * pyramid-add-blocked-by-risk-cap event AND a fresh-entry-blocked-by-risk-cap event get. Three of
 * {@code RiskService}'s four audited threshold rails — daily-loss, profit-target, heat-cap — write a
 * {@code risk_audit} row AND push an ntfy alert on trip; the fourth, deployment, audits only (no
 * alert, {@code RiskService.java:188}). Before this method existed, the pyramid-add risk-cap block
 * matched NEITHER group — it reached only the application log. This joins the audit+alert group.
 * Coverage/visibility only: it does not change the 6% (or any other) pyramid-risk-cap THRESHOLD. The
 * ADD call site stays unreachable in production while pyramiding is disabled by default ({@code
 * artha.manas-arora.pyramid.enabled=false}); the FRESH-entry call site is live regardless of that flag
 * (see {@code ManasAroraSwingEngineTest}'s {@code aPyramidAddIsBlockedWhenItWouldBreachTheOpenRiskCap}
 * and {@code aFreshEntryAtSixOpenPositionsIsRefusedWhenTheSeventhWouldBreachTheOpenRiskCap} for the
 * engine-to-port wiring proof of each — those tests mock {@code EmissionGuard}, proving {@code
 * SwingBatchEngine} calls the port correctly, not the complete paper-adapter path; this test covers
 * the {@code RiskService} half directly, shared by both call sites).
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
