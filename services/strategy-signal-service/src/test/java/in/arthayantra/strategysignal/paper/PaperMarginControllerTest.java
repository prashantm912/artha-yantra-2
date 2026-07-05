package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperMarginController.MarginHeat;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * F9 advisory heat read: empty book → unpriced without a margin call; an open book is priced as one
 * basket of tradingsymbol legs and the aggregate is passed through with the position counts.
 */
class PaperMarginControllerTest {

  private static PositionRow pos(String sym, String side, long qty) {
    return new PositionRow(
        1L, "NFO", sym, side, qty, new BigDecimal("100"), BigDecimal.ZERO, "OPEN",
        OffsetDateTime.parse("2026-07-03T09:20:00+05:30"), null, null, null, null, "scalper");
  }

  @Test
  void emptyBookIsUnpricedWithoutCallingMarketData() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen()).thenReturn(List.of());

    MarginHeat h = new PaperMarginController(repo, client).marginHeat();

    assertThat(h.priced()).isFalse();
    assertThat(h.openPositions()).isZero();
    verify(client, never()).margin(any());
  }

  @Test
  void openBookIsPricedAsOneBasketOfSymbolLegs() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen())
        .thenReturn(List.of(pos("NIFTY2570725000CE", "SELL", 65), pos("NIFTY2570725200PE", "BUY", 65)));
    when(client.margin(any()))
        .thenReturn(
            new PaperMarginClient.Quote(
                true, null, new BigDecimal("99381.1"), new BigDecimal("31554.38"),
                new BigDecimal("130935.48"), new BigDecimal("130935.48"),
                new BigDecimal("130821.73")));

    MarginHeat h = new PaperMarginController(repo, client).marginHeat();

    assertThat(h.priced()).isTrue();
    assertThat(h.spanMargin()).isEqualByComparingTo("99381.1");
    assertThat(h.openPositions()).isEqualTo(2);
    assertThat(h.pricedLegs()).isEqualTo(2);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PaperMarginClient.Leg>> captor = ArgumentCaptor.forClass(List.class);
    verify(client).margin(captor.capture());
    assertThat(captor.getValue())
        .extracting(PaperMarginClient.Leg::tradingsymbol, PaperMarginClient.Leg::side)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("NIFTY2570725000CE", "SELL"),
            org.assertj.core.groups.Tuple.tuple("NIFTY2570725200PE", "BUY"));
  }
}
