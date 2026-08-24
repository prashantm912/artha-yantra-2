package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Read endpoints default to the latest PERSISTED screen date, not the bhavcopy watermark (audit
 * H1 2026-07-05 empty-funnel race — see the Minervini twin test for the full story).
 */
class ManasControllerDefaultDateTest {

  private final ManasScreenService screener = mock(ManasScreenService.class);
  private final ManasScreenRepository repo = mock(ManasScreenRepository.class);
  private final ManasFunnelService funnelService = mock(ManasFunnelService.class);

  /** Shared with the scheduler in production; held by the test in {@link #manualRunWaitsForTheScreenLock()}. */
  private final ManasScreenLock screenLock = new ManasScreenLock();

  private ManasController controller() {
    return new ManasController(
        screener,
        repo,
        mock(ManasGeometryService.class),
        mock(ManasSetupsRepository.class),
        funnelService,
        mock(ManasAroraBacktestService.class),
        mock(in.arthayantra.marketdata.screener.ScreenerHistoryRepository.class),
        // ⚠️ A REAL lock. This door calls lock(), not tryLock() — a MOCK makes lock() a no-op, so
        // the call would proceed UNBLOCKED and manualRunWaitsForTheScreenLock would fail. Not
        // "skip": that is the scheduled doors' behaviour, and an earlier version of this comment
        // named the wrong method and the wrong outcome.
        screenLock);
  }

  /**
   * ⚠️ LEDGER H13, the FOURTH door. {@code POST /run} publishes the screen ITSELF — it calls
   * {@code screen} then {@code replaceAll} inline rather than delegating the way the minervini
   * controller delegates to {@code MinerviniScheduler.runOnce}. So a lock living only on
   * {@code ManasScheduler} would leave this door open, and the review of #1456 caught exactly that:
   * a lock covering every door but one reads as solved.
   *
   * <p>Concurrency here is not merely duplicated work. {@code ManasScreenRepository.replaceAll} is a
   * DELETE-by-date plus a batch upsert in ONE transaction, so under READ COMMITTED the second
   * transaction's DELETE cannot see rows the first inserted after its snapshot and the two candidate
   * sets MERGE — a symbol the trailing-bar guard dropped survives into the published screen.
   *
   * <p>The assertion is that the call BLOCKS while the lock is held and completes once it is
   * released. Holding the lock from the test thread is what makes this reachable at all.
   */
  @Test
  void manualRunWaitsForTheScreenLock() throws Exception {
    when(screener.screen(null))
        .thenReturn(new ManasScreenService.ScreenResult(LocalDate.of(2026, 8, 24), 0, List.of()));
    ManasController c = controller();
    java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

    screenLock.lock();
    Thread caller =
        new Thread(
            () -> {
              c.run(null, true, 50);
              finished.countDown();
            },
            "manual-run");
    try {
      caller.start();
      assertThat(finished.await(750, java.util.concurrent.TimeUnit.MILLISECONDS))
          .as("POST /run must NOT publish while another door holds the screen lock")
          .isFalse();
      verify(screener, org.mockito.Mockito.never()).screen(null);
    } finally {
      screenLock.unlock();
    }

    assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        .as("…and it must proceed as soon as the lock is released, never skip the recompute")
        .isTrue();
    caller.join(5_000);
    verify(screener).screen(null);
    verify(repo).replaceAll(eq(LocalDate.of(2026, 8, 24)), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void funnelDefaultsToThePersistedScreenDateNotTheBhavcopyWatermark() {
    LocalDate persisted = LocalDate.of(2026, 7, 3);
    LocalDate bhavcopy = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(persisted);
    when(screener.latestScreenDate()).thenReturn(bhavcopy);
    when(funnelService.funnel(persisted))
        .thenReturn(
            new ManasFunnelService.Funnel(persisted, null, List.of(), List.of(), List.of()));

    ManasFunnelService.Funnel funnel = controller().funnel(null);

    verify(funnelService).funnel(persisted);
    assertThat(funnel.screenDate()).isEqualTo(persisted);
  }

  @Test
  void funnelFallsBackToTheBhavcopyWatermarkWhenNoScreenEverPersisted() {
    LocalDate bhavcopy = LocalDate.of(2026, 7, 6);
    when(repo.latestScreenDate()).thenReturn(null);
    when(screener.latestScreenDate()).thenReturn(bhavcopy);
    when(funnelService.funnel(bhavcopy))
        .thenReturn(
            new ManasFunnelService.Funnel(bhavcopy, null, List.of(), List.of(), List.of()));

    assertThat(controller().funnel(null).screenDate()).isEqualTo(bhavcopy);
  }

  @Test
  void screenRowSerializesRsRankFromTheDbRowNullableHonest() {
    LocalDate date = LocalDate.of(2026, 7, 10);
    when(repo.latestScreenDate()).thenReturn(date);
    when(repo.coverage(date)).thenReturn(2);
    when(repo.latest(eq(date), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(List.of(candidate("RANKED", new BigDecimal("87.50")), candidate("UNRANKED", null)));

    ManasController.ScreenResponse res = controller().get(null, true, 50, 0);

    assertThat(res.items()).hasSize(2);
    assertThat(res.items().get(0).rsRank()).isEqualByComparingTo("87.50");
    assertThat(res.items().get(1).rsRank()).isNull(); // a genuinely unranked row stays null, never fabricated
  }

  /** A minimal persisted candidate carrying only the fields the rsRank pass-through touches. */
  private static ManasCandidate candidate(String symbol, BigDecimal rsRank) {
    return new ManasCandidate(
        symbol, "NSE", null, null, null, null, null, null, null, null, null, null,
        false, false, false, false, false, new boolean[6], 0, true, null, null, rsRank);
  }
}
