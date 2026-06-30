package in.arthayantra.strategysignal.paper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.StraddleLegRow;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient.StraddleSl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StraddleExitMonitorTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final String DETAIL =
      "{\"side\":\"NEUTRAL\",\"underlying\":\"NIFTY 50\",\"expiry\":\"2026-07-07\",\"strike\":23900}";

  private final PaperPositionRepository positions = mock(PaperPositionRepository.class);
  private final MarketDataCandlesClient marketData = mock(MarketDataCandlesClient.class);
  private final PaperService paper = mock(PaperService.class);

  private final StraddleExitMonitor monitor =
      new StraddleExitMonitor(positions, marketData, paper, M, Clock.systemUTC().withZone(ZoneOffset.UTC));

  @Test
  void closesBothLegsWhenTheCombinedPremiumHitsTheStop() {
    when(positions.openStraddleLegs())
        .thenReturn(List.of(new StraddleLegRow(101, 7, DETAIL), new StraddleLegRow(102, 7, DETAIL)));
    // combined 140 <= slLevel 150 → the combined-prem stop is hit.
    when(marketData.straddleSl(any(), any(), any()))
        .thenReturn(Optional.of(new StraddleSl(new BigDecimal("150"), new BigDecimal("140"))));

    monitor.evaluate();

    verify(paper).closePosition(eq(101L), isNull());
    verify(paper).closePosition(eq(102L), isNull());
  }

  @Test
  void closesNothingWhenAboveTheStop() {
    when(positions.openStraddleLegs())
        .thenReturn(List.of(new StraddleLegRow(101, 7, DETAIL), new StraddleLegRow(102, 7, DETAIL)));
    // combined 160 > slLevel 150 → still in the trade.
    when(marketData.straddleSl(any(), any(), any()))
        .thenReturn(Optional.of(new StraddleSl(new BigDecimal("150"), new BigDecimal("160"))));

    monitor.evaluate();

    verify(paper, never()).closePosition(anyLong(), any());
  }

  @Test
  void noOpenStraddlesMakesNoMarketDataCall() {
    when(positions.openStraddleLegs()).thenReturn(List.of());

    monitor.evaluate();

    verify(marketData, never()).straddleSl(any(), any(), any());
    verify(paper, never()).closePosition(anyLong(), any());
  }

  @Test
  void aMissingSlReadDoesNotExit() {
    when(positions.openStraddleLegs())
        .thenReturn(List.of(new StraddleLegRow(101, 7, DETAIL), new StraddleLegRow(102, 7, DETAIL)));
    when(marketData.straddleSl(any(), any(), any())).thenReturn(Optional.empty());

    monitor.evaluate();

    verify(paper, never()).closePosition(anyLong(), any());
  }
}
