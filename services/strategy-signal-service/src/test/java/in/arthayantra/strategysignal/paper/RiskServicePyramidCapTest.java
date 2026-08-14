package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.notifier.NotifierClient;
import java.math.BigDecimal;
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
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, new BigDecimal("6.0"),
            new ManasGoverningStopCache(), new PyramidRiskCapAuditor(settings, notifier));
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

  /**
   * The 2026-08-13 fix, pinned against the LIVE measurement that motivated it. On the 08:35 IST
   * catch-up for session 2026-08-12 the manas-arora entry pass refused exactly these four names
   * (batch summary {@code would-enter 4, admitted 0, cap-exceedance 4}) and {@code
   * strategy.risk_audit} recorded ONE TRIP row, for BIRLACABLE — a 4:1 undercount in the table the
   * owner tunes the cap from. Each distinct refused symbol must now leave its own row.
   *
   * <p>The ALERT half is asserted in the same test on purpose: the fix must NOT convert a 4-refusal
   * run into 4 ntfy pushes. Row count and push count move independently, which is the whole point of
   * splitting the two dedup keys, so a test that pinned only one of them would pass while the other
   * regressed.
   */
  @Test
  void everyDistinctSymbolRefusedTheSameDayGetsItsOwnAuditRowButOnlyOneAlert() {
    Harness h = harness();

    h.risk().recordPyramidRiskCapBreach(BOOK, "BIRLACABLE", "fresh entry for BIRLACABLE blocked");
    h.risk().recordPyramidRiskCapBreach(BOOK, "HAPPYFORGE", "fresh entry for HAPPYFORGE blocked");
    h.risk().recordPyramidRiskCapBreach(BOOK, "BLUSPRING", "fresh entry for BLUSPRING blocked");
    h.risk().recordPyramidRiskCapBreach(BOOK, "AUTOIND", "fresh entry for AUTOIND blocked");

    verify(h.settings(), times(4))
        .audit(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), eq("TRIP"), any());
    for (String symbol : new String[] {"BIRLACABLE", "HAPPYFORGE", "BLUSPRING", "AUTOIND"}) {
      verify(h.settings())
          .audit(
              eq(BOOK),
              eq(RiskService.PYRAMID_RISK_CAP),
              eq("TRIP"),
              eq("fresh entry for " + symbol + " blocked"));
    }
    verify(h.notifier(), times(1)).send(eq("NTFY"), any(), any());
  }

  /**
   * The row grain is per SYMBOL per IST day, not per call: a repeat refusal of the same name the same
   * day (the 20:05 run and a later catch-up both refusing it) is one measurement, not two, and must
   * not inflate the count the owner reads.
   */
  @Test
  void theSameSymbolRefusedTwiceTheSameDayIsStillDeduped() {
    Harness h = harness();

    h.risk().recordPyramidRiskCapBreach(BOOK, "BIRLACABLE", "first refusal");
    h.risk().recordPyramidRiskCapBreach(BOOK, "BIRLACABLE", "same name, same IST day");

    verify(h.settings(), times(1))
        .audit(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), eq("TRIP"), any());
    verify(h.notifier(), times(1)).send(eq("NTFY"), any(), any());
  }

  @Test
  void neverCalledMeansNeverAudited() {
    Harness h = harness();
    verify(h.settings(), never()).audit(any(), any(), any(), any());
  }

  /**
   * Round 4, cross-vendor review Major 3: the fail-soft catch now lives at {@code
   * RiskService#recordPyramidRiskCapBreach}, wrapping the PROXIED call to the separate {@link
   * PyramidRiskCapAuditor} bean — a genuine outer boundary, unlike round 3's catch INSIDE the
   * REQUIRES_NEW method's own body (which could not catch a transaction-interceptor failure at
   * commit/connection-acquisition time, since that throws from outside the method's stack frame).
   * A mocked auditor that throws stands in for exactly that class of failure: whatever throws it,
   * the exception must never reach either caller ({@code PaperService#openOrder}, mid-refusal, or
   * {@code SwingBatchEngine}'s entry pass, the batch's only exit evaluator), and the per-day dedup
   * key must NOT be consumed on a write that did not durably land — a retry later the same day
   * should still get one real attempt.
   */
  @Test
  void aFailureInTheProxiedAuditorCallNeverPropagatesAndNeverConsumesTheDedupKey() {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    PyramidRiskCapAuditor auditor = mock(PyramidRiskCapAuditor.class);
    // First call fails (simulating a commit/connection-acquisition failure the caller cannot avoid);
    // the second (same-day retry) succeeds — proving the failed attempt did not consume the dedup key.
    doThrow(new RuntimeException("simulated connection/commit failure"))
        .doNothing()
        .when(auditor)
        .record(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), any(), anyBoolean());
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, new BigDecimal("6.0"),
            new ManasGoverningStopCache(), auditor);

    assertThatCode(() -> risk.recordPyramidRiskCapBreach(BOOK, "RELIANCE", "first attempt"))
        .as("the proxied auditor's failure must never propagate to the caller")
        .doesNotThrowAnyException();

    risk.recordPyramidRiskCapBreach(BOOK, "RELIANCE", "retry, same day");
    verify(auditor, times(2))
        .record(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), any(), anyBoolean());
  }

  /**
   * The failed write must consume NEITHER dedup key (2026-08-13). The pre-existing test above proves
   * the SYMBOL key survives a failure; this one proves the ALERT key does too. Without it, a first
   * refusal whose write failed could mark the book as "already alerted" and silently downgrade the
   * day's only surviving ntfy push — the alert half of the same "never consume a key on a write that
   * did not durably land" rule the method's javadoc states.
   */
  @Test
  void aFailedWriteConsumesNeitherTheSymbolNorTheAlertDedupKey() {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    PyramidRiskCapAuditor auditor = mock(PyramidRiskCapAuditor.class);
    doThrow(new RuntimeException("simulated connection/commit failure"))
        .doNothing()
        .when(auditor)
        .record(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), any(), anyBoolean());
    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false, new BigDecimal("6.0"),
            new ManasGoverningStopCache(), auditor);

    risk.recordPyramidRiskCapBreach(BOOK, "BIRLACABLE", "first attempt, write fails");
    risk.recordPyramidRiskCapBreach(BOOK, "HAPPYFORGE", "a DIFFERENT name, same day");

    // The second call must still request the alert: the first never landed, so the day owes one push.
    verify(auditor).record(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), eq("a DIFFERENT name, same day"), eq(true));
  }

  /**
   * A limit change re-arms the per-SYMBOL audit keys as well as the per-book alert key. After the
   * owner edits the cap, a name already refused today was refused against a DIFFERENT cap value, so
   * its next refusal is a genuinely new measurement and must get its own row — the same reasoning the
   * pre-existing {@code trippedOn.remove} in {@code update} already applies to the alert.
   */
  @Test
  void aLimitUpdateReArmsThePerSymbolAuditDedup() {
    Harness h = harness();

    h.risk().recordPyramidRiskCapBreach(BOOK, "BIRLACABLE", "before the cap change");
    h.risk().update(BOOK, RiskService.PYRAMID_RISK_CAP, "{\"value\":8.0,\"enabled\":true}");
    h.risk().recordPyramidRiskCapBreach(BOOK, "BIRLACABLE", "after the cap change");

    verify(h.settings(), times(2))
        .audit(eq(BOOK), eq(RiskService.PYRAMID_RISK_CAP), eq("TRIP"), any());
  }
}
