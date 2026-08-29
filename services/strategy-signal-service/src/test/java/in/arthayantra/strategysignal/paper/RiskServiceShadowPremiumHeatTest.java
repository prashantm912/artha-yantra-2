package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Ledger N23-A: shadow the heat cap against PREMIUM OUTLAY, measure only.
 *
 * <p><b>The defect being measured.</b> The heat cap is computed from SPAN margin, and SPAN is
 * structurally {@code 0.00} on a long-only options book — you pay premium, you do not post margin. So
 * the gate cannot fire, and a "60% heat cap" that never fires reads on the settings panel exactly like
 * a cap that is never breached.
 *
 * <p><b>The property that matters most here is the NEGATIVE one:</b> this must never block. The owner
 * chose to learn the true refusal rate over ~10 sessions before it can cost a trade, so a test that
 * only proved "it logs" would miss the whole point of the ruling.
 */
class RiskServiceShadowPremiumHeatTest {

  private static final String BOOK = "scalper";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T06:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper OM = new ObjectMapper();

  private record Harness(RiskService risk, RiskSettingsRepository settings, NotifierClient notifier) {}

  private static JsonNode json(String raw) {
    try {
      return OM.readTree(raw);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static PaperPositionRepository.PositionRow leg() {
    return new PaperPositionRepository.PositionRow(
        1L, "NFO", "NIFTY26SEP24000CE", "BUY", 50L, BigDecimal.TEN, null, "OPEN",
        null, null, null, null, null, BOOK);
  }

  /**
   * @param capitalUsed drives the premium-outlay ratio; equity is fixed at 150,000
   * @param spanPriced when false the SPAN quote is unpriced, so real enforcement cannot assess
   */
  private static Harness harness(String capPct, String capitalUsed, boolean spanPriced) {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);

    when(settings.get(eq(BOOK), any())).thenReturn(Optional.empty());
    when(settings.get(BOOK, RiskService.HEAT_CAP_PCT))
        .thenReturn(
            Optional.of(
                new Setting(
                    RiskService.HEAT_CAP_PCT,
                    json("{\"enabled\":true,\"value\":" + capPct + "}"),
                    null)));
    when(positions.listOpen(BOOK)).thenReturn(List.of(leg()));
    when(positions.openCount(BOOK)).thenReturn(1);
    when(account.equity(BOOK)).thenReturn(new BigDecimal("150000"));
    when(account.capitalUsed(BOOK)).thenReturn(new BigDecimal(capitalUsed));
    // The live shape: a long-only book prices fine but SPAN margin is ZERO, so real heat is 0%.
    when(margin.margin(any()))
        .thenReturn(
            spanPriced
                // priced, but spanMargin ZERO -- the live long-only shape.
                ? new PaperMarginClient.Quote(
                    true, null, BigDecimal.ZERO, null, null, null, null, null, null, null)
                : new PaperMarginClient.Quote(
                    false, "unpriced", null, null, null, null, null, null, null, null));
    when(notifier.configured("NTFY")).thenReturn(true);

    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, true,
            new BigDecimal("6.0"), new ManasGoverningStopCache(),
            new PyramidRiskCapAuditor(settings, notifier));
    return new Harness(risk, settings, notifier);
  }

  /**
   * ⚠️ THE POINT OF THE RULING: over the cap on premium outlay, and still admitted. If this ever goes
   * red the shadow has become enforcement, which is the one thing the owner ruled out until ~10
   * sessions of evidence exist.
   */
  @Test
  void aBookOverTheCapOnPremiumOutlayIsStillADMITTED() {
    // 120,000 / 150,000 = 80% premium outlay, against a 60% cap.
    Harness h = harness("60", "120000", true);

    assertThat(h.risk().entryVeto(BOOK))
        .as("the shadow measures; it must never veto")
        .isEmpty();
  }

  @Test
  void itRecordsWhatItWouldHaveBlocked() {
    Harness h = harness("60", "120000", true);

    h.risk().entryVeto(BOOK);

    verify(h.settings())
        .audit(eq(BOOK), eq(RiskService.HEAT_CAP_PCT), eq("SHADOW"), contains("WOULD have blocked"));
  }

  /**
   * ⚠️ It must never page either. This is a measurement accruing over ~10 sessions; alerting daily on
   * a condition that is expected to be true most days is how the channel gets ignored.
   */
  @Test
  void theShadowNeverPages() {
    Harness h = harness("60", "120000", true);

    h.risk().entryVeto(BOOK);

    verify(h.notifier(), never()).send(anyString(), anyString(), anyString());
  }

  /** Deduped per book per day — at 80% outlay EVERY entry attempt would otherwise write a row. */
  @Test
  void itWritesOneRowPerDayNotOnePerEntryAttempt() {
    Harness h = harness("60", "120000", true);

    for (int i = 0; i < 20; i++) {
      h.risk().entryVeto(BOOK);
    }

    verify(h.settings(), times(1))
        .audit(eq(BOOK), eq(RiskService.HEAT_CAP_PCT), eq("SHADOW"), anyString());
  }

  /** The control: under the cap on premium outlay, nothing is recorded. */
  @Test
  void aBookUnderTheCapRecordsNothing() {
    // 45,000 / 150,000 = 30%, under a 60% cap.
    Harness h = harness("60", "45000", true);

    h.risk().entryVeto(BOOK);

    verify(h.settings(), never())
        .audit(anyString(), anyString(), eq("SHADOW"), anyString());
  }

  /**
   * ⚠️ The shadow depends on the SPAN path having produced an assessable number first, because it
   * runs in the same branch. When SPAN is unpriced, real enforcement takes the UNPRICED path and the
   * shadow is not reached — a real gap in the measurement, recorded here rather than left implicit,
   * because it means the ~10-session rate under-counts if the margin endpoint is flaky.
   */
  @Test
  void anUnpricedSpanQuoteSkipsTheShadowToo() {
    Harness h = harness("60", "120000", false);

    h.risk().entryVeto(BOOK);

    verify(h.settings(), never())
        .audit(anyString(), anyString(), eq("SHADOW"), anyString());
  }
}
