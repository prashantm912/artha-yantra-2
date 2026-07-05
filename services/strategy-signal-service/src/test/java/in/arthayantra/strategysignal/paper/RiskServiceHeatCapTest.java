package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.notifier.NotifierClient;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.paper.RiskSettingsRepository.Setting;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the F9 portfolio heat-cap gate in {@link RiskService#entryAllowed}. Mocks the
 * margin client + notifier so the branch (advisory vs enforcing, priced vs unpriced) is exercised
 * without a live market-data call. The other governors are stubbed absent so only the heat cap acts.
 */
class RiskServiceHeatCapTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC);
  private static final String BOOK = "scalper";

  private static JsonNode json(String s) {
    try {
      return JSON.readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static PositionRow openLeg() {
    return new PositionRow(
        1L, "NFO", "NIFTY26JUL25000CE", "BUY", 50L, BigDecimal.TEN, null, "OPEN",
        null, null, null, null, null, BOOK);
  }

  /** A RiskService wired so every governor except the heat cap is absent (settings.get → empty). */
  private static Harness harness(boolean enforcementEnabled, String heatCapJson) {
    RiskSettingsRepository settings = mock(RiskSettingsRepository.class);
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperAccountService account = mock(PaperAccountService.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    NotifierClient notifier = mock(NotifierClient.class);
    when(settings.get(eq(BOOK), any())).thenReturn(Optional.empty());
    if (heatCapJson != null) {
      when(settings.get(BOOK, RiskService.HEAT_CAP_PCT))
          .thenReturn(Optional.of(new Setting(RiskService.HEAT_CAP_PCT, json(heatCapJson), null)));
    }
    when(positions.listOpen(BOOK)).thenReturn(List.of(openLeg()));
    when(account.equity(BOOK)).thenReturn(new BigDecimal("150000"));
    when(notifier.configured("NTFY")).thenReturn(true);
    RiskService risk =
        new RiskService(settings, positions, account, margin, notifier, CLOCK, enforcementEnabled);
    return new Harness(risk, settings, margin, notifier);
  }

  private record Harness(
      RiskService risk,
      RiskSettingsRepository settings,
      PaperMarginClient margin,
      NotifierClient notifier) {}

  private static PaperMarginClient.Quote priced(String span) {
    return new PaperMarginClient.Quote(
        true, null, new BigDecimal(span), BigDecimal.ZERO, new BigDecimal(span), new BigDecimal(span),
        new BigDecimal(span));
  }

  @Test
  void underCapAllows() {
    Harness h = harness(true, "{\"enabled\": true, \"value\": 60.0}");
    // span 60k / equity 150k = 40% < 60% cap
    when(h.margin().margin(anyList())).thenReturn(priced("60000"));
    assertThat(h.risk().entryAllowed(BOOK)).isTrue();
    verify(h.settings(), never()).audit(any(), any(), any(), any());
  }

  @Test
  void overCapWithEnforcementBlocksAndAlerts() {
    Harness h = harness(true, "{\"enabled\": true, \"value\": 60.0}");
    // span 100k / equity 150k = 66.67% ≥ 60% cap
    when(h.margin().margin(anyList())).thenReturn(priced("100000"));
    assertThat(h.risk().entryAllowed(BOOK)).isFalse();
    verify(h.settings()).audit(eq(BOOK), eq(RiskService.HEAT_CAP_PCT), eq("TRIP"), any());
    verify(h.notifier()).send(eq("NTFY"), any(), any());
  }

  @Test
  void enforcementOffSkipsHeatCheckEntirely() {
    // Master flag OFF (default): the heat block is not even priced — no market-data HTTP call on the
    // emission hot path, no audit, never blocks. The owner watches heat via GET /paper/margin-heat.
    Harness h = harness(false, "{\"enabled\": true, \"value\": 60.0}");
    assertThat(h.risk().entryAllowed(BOOK)).isTrue();
    verify(h.margin(), never()).margin(anyList());
    verify(h.settings(), never()).audit(any(), any(), any(), any());
  }

  @Test
  void unpricedNeverBlocks() {
    Harness h = harness(true, "{\"enabled\": true, \"value\": 60.0}");
    when(h.margin().margin(anyList())).thenReturn(PaperMarginClient.Quote.unpriced("analytics off"));
    assertThat(h.risk().entryAllowed(BOOK)).isTrue();
    verify(h.settings(), never()).audit(any(), any(), any(), any());
  }

  @Test
  void disabledHeatCapSkipsMarginCall() {
    Harness h = harness(true, "{\"enabled\": false, \"value\": 60.0}");
    assertThat(h.risk().entryAllowed(BOOK)).isTrue();
    verify(h.margin(), never()).margin(anyList());
  }
}
