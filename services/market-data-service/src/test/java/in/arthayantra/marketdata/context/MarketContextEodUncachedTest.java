package in.arthayantra.marketdata.context;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.context.DayContextService.DayContext;
import in.arthayantra.marketdata.context.DayContextService.IngestTrust;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that the EOD day-context row is built from an UNCACHED read.
 *
 * <p>⚠️ <b>Why this is its own test rather than a comment.</b> {@code market_context_days} holds
 * exactly one row per session and it is supposed to be the day's CLOSING context. After H31 added
 * the intraday snapshot, that only stayed true by arithmetic across knobs the job does not own: the
 * refresher's window ends at 15:58, the job runs at 18:49, and
 * {@code artha.context.day-context-snapshot-max-age-seconds} is 300. Widen
 * {@code artha.context.day-context-refresh-cron} past 18:xx, or raise the max age, and the job
 * would silently begin persisting a mid-afternoon context as the close — <b>with no commit to this
 * job, no deploy of it, and nothing for a reviewer to look at.</b> That is the
 * behaviour-that-arms-itself shape: a config-gated rule that changes what gets written on a date
 * nobody chose.
 *
 * <p>{@link DayContextService#freshDayContext()} removes the dependency instead of documenting it,
 * and this test is what stops the call site drifting back.
 */
class MarketContextEodUncachedTest {

  @Test
  @DisplayName("the EOD job reads the UNCACHED day context, never the snapshot-eligible one")
  void theEodJobUsesTheUncachedEntryPoint() {
    DayContextService dayContext = mock(DayContextService.class);
    MarketContextDayRepository repository = mock(MarketContextDayRepository.class);
    IngestRunLedger ledger = mock(IngestRunLedger.class);
    when(dayContext.freshDayContext()).thenReturn(openContext());
    when(dayContext.dayContext()).thenReturn(openContext());
    when(repository.upsert(any(), anyString(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    // The ledger wraps the job in a RUNNING -> SUCCESS lifecycle; run the callable so persist()
    // actually executes. A bare mock would swallow it and this test would verify nothing.
    doAnswer(
            invocation -> {
              invocation.getArgument(1, IngestRunLedger.IngestJob.class).run();
              return null;
            })
        .when(ledger)
        .record(anyString(), any());

    new MarketContextEodJob(
            dayContext,
            repository,
            ledger,
            java.time.Clock.systemUTC(),
            "0 49 18 * * MON-FRI",
            "NIFTY 50")
        .run();

    verify(dayContext).freshDayContext();
    // ⚠️ The never() is the load-bearing half. Without it an implementation that called BOTH — or
    // that called dayContext() and happened to get a stale-enough snapshot — still satisfies the
    // positive verify, which is precisely the drift this guard exists to catch.
    verify(dayContext, never()).dayContext();
    verify(repository).upsert(any(), anyString(), any(), any(), any(), any(), any(), any());
  }

  /** A minimal non-HOLIDAY context — enough to reach persistRow() without a real digest. */
  private static DayContext openContext() {
    return new DayContext(
        LocalDate.of(2026, 8, 20),
        "OPEN",
        null,
        null,
        null,
        List.of(),
        null,
        new IngestTrust("OK", List.of()),
        OffsetDateTime.parse("2026-08-20T18:49:00+05:30"),
        List.of());
  }
}
