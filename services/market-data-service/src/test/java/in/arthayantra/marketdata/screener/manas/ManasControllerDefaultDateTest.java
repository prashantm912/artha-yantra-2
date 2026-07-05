package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

  private ManasController controller() {
    return new ManasController(
        screener,
        repo,
        mock(ManasGeometryService.class),
        mock(ManasSetupsRepository.class),
        funnelService,
        mock(ManasAroraBacktestService.class));
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
}
