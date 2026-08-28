package in.arthayantra.strategysignal.paper;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the F9 margin annotator's stamp-vs-clear branch (EXT-03 finding 2). A priced quote
 * stamps {@code margin_snapshot}/{@code margin_pct}; an UNPRICED re-quote must CLEAR them (null) rather
 * than leave the prior priced value — an averaged add re-fires {@code PaperPositionOpened} with the
 * grown qty, so a preserved snapshot would be stale/understated and get summed into the risk-heat
 * insight. Mocks the collaborators so the listener runs synchronously off the Spring async/txn path.
 */
class PaperMarginAnnotatorTest {

  private static PaperPositionOpened opened(long id) {
    return new PaperPositionOpened(
        id, "scalper", "NFO", "NIFTY26JUL25000CE", "BUY", 100L,
        in.arthayantra.strategyengine.fills.InstrumentClass.OPTION);
  }

  @Test
  void pricedQuoteStampsTheSnapshot() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    PaperAccountService account = mock(PaperAccountService.class);
    when(margin.margin(anyList()))
        .thenReturn(
            new PaperMarginClient.Quote(
                true, null, new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("30000"),
                new BigDecimal("30000"), new BigDecimal("30000")));
    when(account.equity("scalper")).thenReturn(new BigDecimal("150000"));

    new PaperMarginAnnotator(positions, margin, account).onPaperPositionOpened(opened(7L));

    // span 30000 / equity 150000 = 20.00% of book equity.
    verify(positions)
        .updateMarginSnapshot(eq(7L), eq(new BigDecimal("30000")), eq(new BigDecimal("20.00")));
  }

  @Test
  void unpricedReQuoteClearsThePriorSnapshot() {
    PaperPositionRepository positions = mock(PaperPositionRepository.class);
    PaperMarginClient margin = mock(PaperMarginClient.class);
    PaperAccountService account = mock(PaperAccountService.class);
    when(margin.margin(anyList()))
        .thenReturn(PaperMarginClient.Quote.unpriced("Upstox returned a zero/empty margin basket"));

    new PaperMarginAnnotator(positions, margin, account).onPaperPositionOpened(opened(7L));

    // EXT-03 finding 2: CLEAR (null, null) — never preserve the earlier priced value as a stale number.
    verify(positions).updateMarginSnapshot(eq(7L), isNull(), isNull());
  }
}
