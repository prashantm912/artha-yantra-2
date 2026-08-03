package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code POST /api/v1/market/screener/minervini/run} to the SINGLE orchestration.
 *
 * <p>The controller used to repeat the screen → upsert → geometry sequence inline, so {@link
 * MinerviniScheduler#runOnce} — the method carrying the geometry-consistency and plane-divergence
 * guarantees — had <b>no production caller at all</b>. Its own javadoc claimed the controller used
 * it. Every guarantee added to it therefore skipped the one path a human triggers by hand, and did
 * so silently: nothing failed, the endpoint just quietly did less.
 *
 * <p>Two assertions, and they separate the two ways this regresses: the endpoint must go THROUGH
 * the scheduler, and it must not do the work itself.
 */
class MinerviniRunEndpointTest {

  private final TrendTemplateService screener = mock(TrendTemplateService.class);
  private final MinerviniScreenRepository repo = mock(MinerviniScreenRepository.class);
  private final MinerviniGeometryService geometry = mock(MinerviniGeometryService.class);
  private final MinerviniScheduler scheduler = mock(MinerviniScheduler.class);

  private MinerviniController controller() {
    return new MinerviniController(
        screener,
        repo,
        geometry,
        mock(MinerviniSetupsRepository.class),
        mock(MinerviniFunnelService.class),
        mock(MinerviniHitRateService.class),
        mock(MinerviniBacktestService.class),
        mock(in.arthayantra.marketdata.screener.ScreenerHistoryRepository.class),
        mock(PlaneDivergenceProbe.class),
        scheduler);
  }

  @Test
  void postRunDelegatesToTheInstrumentedOrchestration() {
    LocalDate day = LocalDate.of(2026, 7, 6);
    when(scheduler.runOnce(day))
        .thenReturn(new TrendTemplateService.ScreenResult(day, 7, List.of()));
    // Also stub the INLINE path so that, if the controller regresses to doing the work itself, it
    // succeeds and this test reds on the `verify` below rather than on an NPE. A red-proof that
    // fires because a mock was unstubbed proves nothing about the behaviour under test.
    when(screener.screen(day)).thenReturn(new TrendTemplateService.ScreenResult(day, 7, List.of()));

    MinerviniController.ScreenResponse res = controller().run(day, true, null, 50);

    verify(scheduler).runOnce(day);
    assertThat(res.screenDate()).isEqualTo(day);
    assertThat(res.coverage()).isEqualTo(7);

    // ...and it does NOT re-run the screen or persist anything itself — that duplication is what
    // stranded runOnce as dead code in the first place.
    verify(screener, never()).screen(any());
    verify(repo, never()).upsertAll(any(), any());
    verify(geometry, never()).persistForPassers(any(), any());
  }

  /** A date with no equity data must still short-circuit cleanly through the delegated path. */
  @Test
  void postRunOnAnUnscreenableDateReturnsEmpty() {
    when(scheduler.runOnce(null))
        .thenReturn(new TrendTemplateService.ScreenResult(null, 0, List.of()));
    when(screener.screen(null)).thenReturn(new TrendTemplateService.ScreenResult(null, 0, List.of()));

    MinerviniController.ScreenResponse res = controller().run(null, true, null, 50);

    assertThat(res.items()).isEmpty();
    assertThat(res.screenDate()).isNull();
  }
}
