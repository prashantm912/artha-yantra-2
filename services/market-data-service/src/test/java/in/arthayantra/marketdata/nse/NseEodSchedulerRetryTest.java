package in.arthayantra.marketdata.nse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.feeds.FiiDerivativeFetcher;
import in.arthayantra.marketdata.feeds.FiiDiiFetcher;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The intra-day retry (2026-08-24). A ~22-minute host-network outage spanned the 08:32 startup pull,
 * all three NSE sources failed, and the FII rail read null for the WHOLE session because the next
 * attempt was the 18:46 batch — roughly ten hours later.
 *
 * <p>Both directions matter and neither implies the other: it must RE-PULL what failed, and it must
 * NOT re-pull what already succeeded. The second is not an optimisation — {@code NseEodScheduler}'s
 * own javadoc flags NSE anti-bot behaviour, so re-fetching healthy sources three times a day would
 * trade one defect for another.
 */
class NseEodSchedulerRetryTest {

  private final FiiDiiFetcher fiiDii = mock(FiiDiiFetcher.class);
  private final NseEodFiiDiiRepository fiiDiiRepo = mock(NseEodFiiDiiRepository.class);
  private final ParticipantOiFetcher participantOi = mock(ParticipantOiFetcher.class);
  private final NseEodParticipantOiRepository participantOiRepo =
      mock(NseEodParticipantOiRepository.class);
  private final NseEodFiiDerivativeRepository fiiDerivativeRepo =
      mock(NseEodFiiDerivativeRepository.class);
  private final IngestRunLedger ledger = mock(IngestRunLedger.class);

  @SuppressWarnings("unchecked")
  private NseEodScheduler scheduler() {
    ObjectProvider<FiiDerivativeFetcher> derivative = mock(ObjectProvider.class);
    when(derivative.getIfAvailable()).thenReturn(null); // Upstox-only bean, absent by design here
    return new NseEodScheduler(
        fiiDii, fiiDiiRepo, participantOi, participantOiRepo, derivative, fiiDerivativeRepo, ledger);
  }

  @Test
  @DisplayName("a source that already succeeded today is NOT re-fetched")
  void aSuccessfulSourceIsSkipped() {
    when(ledger.succeededToday(anyString())).thenReturn(true);

    scheduler().retryFailedSources();

    verify(fiiDii, never()).fetchLatest();
    verify(participantOi, never()).fetchLatest();
    verify(ledger, never()).record(anyString(), any());
  }

  @Test
  @DisplayName("a source with no SUCCESS today IS re-fetched, through the audited ledger path")
  void aFailedSourceIsRetried() {
    when(ledger.succeededToday(anyString())).thenReturn(false);
    when(fiiDii.fetchLatest()).thenReturn(List.of());
    when(participantOi.fetchLatest()).thenReturn(List.of());

    scheduler().retryFailedSources();

    // record() wraps each pull, so the retry is audited exactly like the scheduled pull is.
    verify(ledger).record(org.mockito.ArgumentMatchers.eq(IngestRunLedger.SOURCE_NSE_FII_DII), any());
    verify(ledger)
        .record(org.mockito.ArgumentMatchers.eq(IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI), any());
  }

  @Test
  @DisplayName("only the FAILED source is retried when the others are healthy")
  void retryIsPerSourceNotAllOrNothing() {
    when(ledger.succeededToday(IngestRunLedger.SOURCE_NSE_FII_DII)).thenReturn(true);
    when(ledger.succeededToday(IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI)).thenReturn(false);
    when(ledger.succeededToday(IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE)).thenReturn(true);

    scheduler().retryFailedSources();

    verify(ledger, never())
        .record(org.mockito.ArgumentMatchers.eq(IngestRunLedger.SOURCE_NSE_FII_DII), any());
    verify(ledger)
        .record(org.mockito.ArgumentMatchers.eq(IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI), any());
  }

  @Test
  @DisplayName("a retry that ALSO fails is swallowed — the evening batch stays the backstop")
  void aFailingRetryDoesNotPropagate() {
    when(ledger.succeededToday(anyString())).thenReturn(false);
    org.mockito.Mockito.doThrow(new IllegalStateException("NSE still down"))
        .when(ledger)
        .record(anyString(), any());

    // Must not throw: this runs on a scheduler thread, and an escaping exception would suppress
    // every later firing of the same @Scheduled method.
    scheduler().retryFailedSources();
  }
}
