package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperMarginController.MarginHeat;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * F9 advisory heat read: an empty book is unpriced without a margin call; a book's F&O legs are priced
 * as one basket; {@code ?book=} scopes the read; cash-equity legs are excluded (audit M1) and an
 * over-20-leg basket is refused LOUD rather than silently truncated.
 */
class PaperMarginControllerTest {

  private static PositionRow pos(String exchange, String sym, String side, long qty) {
    return new PositionRow(
        1L, exchange, sym, side, qty, new BigDecimal("100"), BigDecimal.ZERO, "OPEN",
        OffsetDateTime.parse("2026-07-03T09:20:00+05:30"), null, null, null, null, "scalper");
  }

  @Test
  void emptyBookIsUnpricedWithoutCallingMarketData() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen(isNull())).thenReturn(List.of());

    MarginHeat h = new PaperMarginController(repo, client).marginHeat(null);

    assertThat(h.priced()).isFalse();
    assertThat(h.openPositions()).isZero();
    verify(client, never()).margin(any());
  }

  @Test
  void openBookIsPricedAsOneBasketOfSymbolLegs() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen(isNull()))
        .thenReturn(
            List.of(
                pos("NFO", "NIFTY2570725000CE", "SELL", 65),
                pos("NFO", "NIFTY2570725200PE", "BUY", 65)));
    when(client.margin(any()))
        .thenReturn(
            new PaperMarginClient.Quote(
                true, null, new BigDecimal("99381.1"), new BigDecimal("31554.38"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("130935.48"), new BigDecimal("130935.48"),
                new BigDecimal("130821.73")));

    MarginHeat h = new PaperMarginController(repo, client).marginHeat(null);

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

  @Test
  void bookParamScopesTheBasketToThatBook() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen(eq("scalper")))
        .thenReturn(List.of(pos("NFO", "NIFTY2570725000CE", "SELL", 65)));
    when(client.margin(any()))
        .thenReturn(
            new PaperMarginClient.Quote(
                true, null, new BigDecimal("50000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50000"),
                new BigDecimal("50000"), new BigDecimal("50000")));

    MarginHeat h = new PaperMarginController(repo, client).marginHeat("scalper");

    assertThat(h.priced()).isTrue();
    assertThat(h.pricedLegs()).isEqualTo(1);
    verify(repo).listOpen(eq("scalper"));
  }

  @Test
  void cashEquityOnlyBookIsUnpricedNotDeadOnASwingLeg() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen(eq("minervini")))
        .thenReturn(List.of(pos("NSE", "RELIANCE", "BUY", 10), pos("BSE", "TCS", "BUY", 5)));

    MarginHeat h = new PaperMarginController(repo, client).marginHeat("minervini");

    assertThat(h.priced()).isFalse();
    assertThat(h.unpricedReason()).contains("cash-equity");
    assertThat(h.openPositions()).isEqualTo(2);
    assertThat(h.pricedLegs()).isZero();
    verify(client, never()).margin(any());
  }

  @Test
  void mixedBookExcludesEquityLegsFromTheSpanBasket() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    when(repo.listOpen(isNull()))
        .thenReturn(
            List.of(
                pos("NFO", "NIFTY2570725000CE", "SELL", 65),
                pos("NSE", "RELIANCE", "BUY", 10)));
    when(client.margin(any()))
        .thenReturn(
            new PaperMarginClient.Quote(
                true, null, new BigDecimal("40000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("40000"),
                new BigDecimal("40000"), new BigDecimal("40000")));

    MarginHeat h = new PaperMarginController(repo, client).marginHeat(null);

    assertThat(h.priced()).isTrue();
    assertThat(h.openPositions()).isEqualTo(2);
    assertThat(h.pricedLegs()).isEqualTo(1); // only the NFO leg is SPAN-margined

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PaperMarginClient.Leg>> captor = ArgumentCaptor.forClass(List.class);
    verify(client).margin(captor.capture());
    assertThat(captor.getValue())
        .extracting(PaperMarginClient.Leg::tradingsymbol)
        .containsExactly("NIFTY2570725000CE");
  }

  @Test
  void overTwentyLegsIsRefusedLoudNeverSilentlyTruncated() {
    PaperPositionRepository repo = mock(PaperPositionRepository.class);
    PaperMarginClient client = mock(PaperMarginClient.class);
    List<PositionRow> legs =
        IntStream.range(0, 21)
            .mapToObj(i -> pos("NFO", "NIFTY257072500" + i + "CE", "SELL", 65))
            .toList();
    when(repo.listOpen(isNull())).thenReturn(legs);

    MarginHeat h = new PaperMarginController(repo, client).marginHeat(null);

    assertThat(h.priced()).isFalse();
    assertThat(h.unpricedReason()).contains("20-leg").contains("21");
    assertThat(h.openPositions()).isEqualTo(21);
    assertThat(h.pricedLegs()).isZero();
    verify(client, never()).margin(any());
  }
}
