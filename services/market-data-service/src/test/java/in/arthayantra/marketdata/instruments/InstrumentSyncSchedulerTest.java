package in.arthayantra.marketdata.instruments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 09:05 IST catch-up pass (F-SYNC / ledger G4).
 *
 * <p>Preserving the {@code kite-dump} permit through an open breaker is necessary but NOT
 * sufficient: {@code CallNotPermittedException} is deliberately non-retryable and the 08:30 job
 * calls {@code runSync()} exactly once, so a breaker rejection used to end the day FAILED with the
 * preserved permit going unspent. This pass is what actually spends it — and it must be inert when
 * the morning run already succeeded, so a healthy day never pays for a second full dump.
 */
class InstrumentSyncSchedulerTest {

  private static final Clock TRADING_DAY =
      Clock.fixed(Instant.parse("2026-07-27T03:35:00Z"), ZoneOffset.UTC); // Mon 09:05 IST

  private static InstrumentSyncService.SyncStatus status(String state) {
    return new InstrumentSyncService.SyncStatus("job", state, Instant.EPOCH, Map.of(), 1L, null);
  }

  private static InstrumentSyncScheduler scheduler(InstrumentSyncService svc) {
    MarketCalendar calendar = mock(MarketCalendar.class);
    when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(true);
    return new InstrumentSyncScheduler(svc, calendar, TRADING_DAY);
  }

  @Test
  void catchUpRerunsTheSyncWhenTheMorningPassFailed() {
    InstrumentSyncService svc = mock(InstrumentSyncService.class);
    when(svc.status()).thenReturn(status("FAILED"));
    when(svc.runSync()).thenReturn(status("OK"));

    scheduler(svc).morningSyncCatchUp();

    verify(svc).runSync();
  }

  @Test
  void catchUpIsInertWhenTheMorningPassSucceeded() {
    InstrumentSyncService svc = mock(InstrumentSyncService.class);
    when(svc.status()).thenReturn(status("OK"));

    scheduler(svc).morningSyncCatchUp();

    verify(svc, never()).runSync();
  }

  /** A still-RUNNING 08:30 pass must never be double-started — one dump burns one scarce permit. */
  @Test
  void catchUpNeverDoubleStartsAnInFlightSync() {
    InstrumentSyncService svc = mock(InstrumentSyncService.class);
    when(svc.status()).thenReturn(status("RUNNING"));

    scheduler(svc).morningSyncCatchUp();

    verify(svc, never()).runSync();
  }

  /** NEVER_RUN (a boot after 08:30) is a legitimate catch-up trigger, not a skip. */
  @Test
  void catchUpRunsWhenTheMorningPassNeverHappened() {
    InstrumentSyncService svc = mock(InstrumentSyncService.class);
    when(svc.status()).thenReturn(status("NEVER_RUN"));
    when(svc.runSync()).thenReturn(status("OK"));

    scheduler(svc).morningSyncCatchUp();

    verify(svc).runSync();
  }

  @Test
  void neitherPassRunsOnAnNseHoliday() {
    InstrumentSyncService svc = mock(InstrumentSyncService.class);
    MarketCalendar calendar = mock(MarketCalendar.class);
    when(calendar.isTradingDay(any(LocalDate.class))).thenReturn(false);
    InstrumentSyncScheduler holiday = new InstrumentSyncScheduler(svc, calendar, TRADING_DAY);

    holiday.morningSync();
    holiday.morningSyncCatchUp();

    verify(svc, never()).runSync();
    assertThat(true).isTrue();
  }
}
