package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Ledger N26 step 1: the two SILENT entry vetoes must leave an audit row.
 *
 * <p>{@code KILL_SWITCH} and {@code MAX_OPEN} returned a verdict and wrote nothing, so a book that had
 * quietly stopped taking entries could only be diagnosed by an investigation rather than a query. Both
 * books were found capacity-bound exactly that way — minervini at 12/12, manas-arora at 6/6 — and each
 * needed one close to admit one entry. The owner chose "make it auditable FIRST", before raising any
 * cap.
 */
class RiskServiceCapacityAuditTest {

  private static final String BOOK = "minervini";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-29T06:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper OM = new ObjectMapper();

  private record Harness(RiskService risk, RiskSettingsRepository settings, NotifierClient notifier) {}

  private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
    try {
      return OM.readTree(raw);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Every governor absent except the one under test. */
  private static Harness harness(boolean killSwitchOn, Integer maxOpenCap, int openCount) {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);

    when(settings.get(eq(BOOK), any())).thenReturn(Optional.empty());
    if (killSwitchOn) {
      when(settings.get(BOOK, RiskService.KILL_SWITCH))
          .thenReturn(
              Optional.of(new Setting(RiskService.KILL_SWITCH, json("{\"enabled\":true}"), null)));
    }
    if (maxOpenCap != null) {
      when(settings.get(BOOK, RiskService.MAX_OPEN))
          .thenReturn(
              Optional.of(
                  new Setting(
                      RiskService.MAX_OPEN, json("{\"enabled\":true,\"value\":" + maxOpenCap + "}"), null)));
    }
    when(positions.openCount(BOOK)).thenReturn(openCount);
    when(account.equity(BOOK)).thenReturn(new BigDecimal("150000"));
    when(notifier.configured("NTFY")).thenReturn(true);

    RiskService risk =
        new RiskService(
            settings, positions, account, margin, notifier, CLOCK, false,
            new BigDecimal("6.0"), new ManasGoverningStopCache(),
            new PyramidRiskCapAuditor(settings, notifier));
    return new Harness(risk, settings, notifier);
  }

  @Test
  void aBookAtCapacityLeavesAnAuditRow() {
    Harness h = harness(false, 12, 12);

    assertThat(h.risk().entryVeto(BOOK)).contains(RiskService.MAX_OPEN);

    verify(h.settings())
        .audit(eq(BOOK), eq(RiskService.MAX_OPEN), eq("TRIP"), anyString());
  }

  @Test
  void theKillSwitchLeavesAnAuditRow() {
    Harness h = harness(true, null, 0);

    assertThat(h.risk().entryVeto(BOOK)).contains(RiskService.KILL_SWITCH);

    verify(h.settings())
        .audit(eq(BOOK), eq(RiskService.KILL_SWITCH), eq("TRIP"), anyString());
  }

  /**
   * ⚠️ The volume property, and the reason this is safe to turn on at all: at cap EVERY candidate is
   * refused, all session. Without the per-(book, rail, day) dedup this would write one row per refused
   * candidate — hundreds a day — and the ledger would be unreadable exactly when it is needed.
   */
  @Test
  void aBookSittingAtCapacityWritesOneRowPerDayNotOnePerRefusal() {
    Harness h = harness(false, 12, 12);

    for (int i = 0; i < 25; i++) {
      h.risk().entryVeto(BOOK);
    }

    verify(h.settings(), times(1))
        .audit(eq(BOOK), eq(RiskService.MAX_OPEN), eq("TRIP"), anyString());
  }

  /**
   * ⚠️ Audited, NOT paged — deliberately unlike a daily-loss trip.
   *
   * <p>A daily-loss trip is an INCIDENT: something changed and someone should look. A capacity cap is a
   * STEADY STATE — a full book stays full until something closes. Paging daily on "the book is full"
   * trains the reader to ignore the channel, and the alerts that matter on this platform are the exits.
   * The row is the deliverable; the alert would be the regression.
   */
  @Test
  void aCapacityVetoIsAuditedButNeverPaged() {
    Harness h = harness(false, 12, 12);

    h.risk().entryVeto(BOOK);

    verify(h.notifier(), never()).send(anyString(), anyString(), anyString());
  }

  /** The control: a book with headroom is not vetoed and writes nothing. */
  @Test
  void aBookWithHeadroomIsNeitherVetoedNorAudited() {
    Harness h = harness(false, 12, 5);

    assertThat(h.risk().entryVeto(BOOK)).isEmpty();

    verify(h.settings(), never()).audit(anyString(), anyString(), anyString(), anyString());
  }
}
