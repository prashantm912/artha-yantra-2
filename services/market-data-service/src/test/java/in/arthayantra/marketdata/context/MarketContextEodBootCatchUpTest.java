package in.arthayantra.marketdata.context;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boot catch-up for the EOD day-context pass (H48 follow-up).
 *
 * <p>⚠️ These tests pin the GUARD, not the persistence — {@code run()} itself is covered elsewhere.
 * The guard is the whole point: a catch-up that fires in the wrong window does not merely fail to
 * help, it writes a PREMATURE row for a session that has not traded, because
 * {@code freshDayContext()} derives its trade date from <em>now</em>. So "does not fire" is the
 * assertion that carries the risk here, and each negative case is its own test rather than sharing a
 * method with the positive one — AssertJ/Mockito stop at the first failure, so bundling them would
 * leave the later ones demonstrated only by inference.
 */
class MarketContextEodBootCatchUpTest {

  /** 2026-09-01 was a Monday — the session the real outage cost. */
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 1);

  private static final String CRON = "0 49 18 * * MON-FRI";

  @Test
  @DisplayName("boot after the slot with no row for today REPLAYS the missed pass")
  void replaysWhenTheSlotPassedAndNoRowExists() {
    Fixture f = new Fixture(MONDAY.atTime(18, 55));
    when(f.repository.existsFor(MONDAY)).thenReturn(false);

    f.job().catchUpOnBoot();

    // The ledger call IS the replay: run() delegates to it, so a recorded MARKET_CONTEXT_DAY pass
    // is the observable proof the catch-up fired.
    verify(f.ledger).record(anyString(), any());
  }

  @Test
  @DisplayName("boot after the slot when today ALREADY has a row does not replay")
  void doesNotReplayWhenTheRowAlreadyExists() {
    Fixture f = new Fixture(MONDAY.atTime(18, 55));
    when(f.repository.existsFor(MONDAY)).thenReturn(true);

    f.job().catchUpOnBoot();

    verify(f.ledger, never()).record(anyString(), any());
  }

  @Test
  @DisplayName("boot BEFORE the slot leaves the pass to the cron")
  void doesNotReplayBeforeTheSlot() {
    // 18:48:59 — one second before the 18:49 slot, which is where the real 2026-09-01 boot landed.
    Fixture f = new Fixture(MONDAY.atTime(LocalTime.of(18, 48, 59)));

    f.job().catchUpOnBoot();

    verify(f.ledger, never()).record(anyString(), any());
    // ⚠️ The repository must not even be CONSULTED before the slot. Asserting only on the ledger
    // would pass for an implementation that checks the row first and skips for the wrong reason.
    verify(f.repository, never()).existsFor(any());
  }

  @Test
  @DisplayName("a Saturday boot does not replay — the cron never fires on that date")
  void doesNotReplayWhenTheCronSkipsThatDate() {
    // 2026-09-05 is a Saturday; a MON-FRI cron has no fire time on it, so there is no missed pass.
    Fixture f = new Fixture(LocalDate.of(2026, 9, 5).atTime(20, 0));

    f.job().catchUpOnBoot();

    verify(f.ledger, never()).record(anyString(), any());
    verify(f.repository, never()).existsFor(any());
  }

  @Test
  @DisplayName("a disabled cron has no schedule to miss, so nothing is replayed")
  void doesNotReplayWhenTheJobIsDisabled() {
    Fixture f = new Fixture(MONDAY.atTime(18, 55), "-");

    f.job().catchUpOnBoot();

    verify(f.ledger, never()).record(anyString(), any());
    verify(f.repository, never()).existsFor(any());
  }

  /** Mocks plus a fixed IST clock, so "now" is the only variable under test. */
  private static final class Fixture {
    final DayContextService dayContext = mock(DayContextService.class);
    final MarketContextDayRepository repository = mock(MarketContextDayRepository.class);
    final IngestRunLedger ledger = mock(IngestRunLedger.class);
    final Clock clock;
    final String cron;

    Fixture(LocalDateTime nowIst) {
      this(nowIst, CRON);
    }

    Fixture(LocalDateTime nowIst, String cron) {
      this.clock = Clock.fixed(nowIst.atZone(Ist.ZONE).toInstant(), Ist.ZONE);
      this.cron = cron;
    }

    MarketContextEodJob job() {
      return new MarketContextEodJob(dayContext, repository, ledger, clock, cron, "NIFTY 50");
    }
  }
}
