package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Read endpoints must default to the latest PERSISTED screen date, not the bhavcopy watermark —
 * between the day's bhavcopy landing and the screen running, the bhavcopy watermark points at a
 * date with ZERO screen rows, which served an empty funnel to the 20:00 swing batch every
 * unattended evening (audit H1 2026-07-05).
 */
class MinerviniControllerDefaultDateTest {

  private final TrendTemplateService screener = mock(TrendTemplateService.class);
  private final MinerviniScreenRepository repo = mock(MinerviniScreenRepository.class);
  private final MinerviniFunnelService funnelService = mock(MinerviniFunnelService.class);

  private MinerviniController controller() {
    return new MinerviniController(
        screener,
        repo,
        mock(MinerviniGeometryService.class),
        mock(MinerviniSetupsRepository.class),
        funnelService,
        mock(MinerviniHitRateService.class),
        mock(MinerviniBacktestService.class),
        mock(in.arthayantra.marketdata.screener.ScreenerHistoryRepository.class),
        mock(PlaneDivergenceProbe.class),
        mock(MinerviniScheduler.class));
  }

  @Test
  void funnelDefaultsToThePersistedScreenDateNotTheBhavcopyWatermark() {
    LocalDate persisted = LocalDate.of(2026, 7, 3);
    LocalDate bhavcopy = LocalDate.of(2026, 7, 6); // fresher bhavcopy, screen not yet run for it
    when(repo.latestScreenDate()).thenReturn(persisted);
    when(screener.latestScreenDate()).thenReturn(bhavcopy);
    when(funnelService.funnel(persisted))
        .thenReturn(
            new MinerviniFunnelService.Funnel(persisted, null, List.of(), List.of(), List.of()));

    MinerviniFunnelService.Funnel funnel = controller().funnel(null);

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
            new MinerviniFunnelService.Funnel(bhavcopy, null, List.of(), List.of(), List.of()));

    assertThat(controller().funnel(null).screenDate()).isEqualTo(bhavcopy);
  }

  @Test
  void explicitAsOfWinsOverBothWatermarks() {
    LocalDate asOf = LocalDate.of(2026, 6, 30);
    when(funnelService.funnel(asOf))
        .thenReturn(new MinerviniFunnelService.Funnel(asOf, null, List.of(), List.of(), List.of()));

    assertThat(controller().funnel(asOf).screenDate()).isEqualTo(asOf);
  }
}
