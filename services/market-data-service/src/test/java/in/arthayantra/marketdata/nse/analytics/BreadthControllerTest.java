package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F3.1: the /breadth/live shape — constituent-universe advance/decline COUNTS (the scalper dot's
 * "advances &gt; 32" operates on ~50, never the full-bhavcopy thousands) + the live/EOD provenance
 * flag from the underlying contribution fold.
 */
class BreadthControllerTest {

  @Test
  void liveBreadthCountsConstituentsAndCarriesProvenance() {
    EquityIndexContributionService contributions = mock(EquityIndexContributionService.class);
    EquityIndexContributionService.ContribRow up =
        new EquityIndexContributionService.ContribRow(
            1, "RELIANCE", new BigDecimal("0.12"), new BigDecimal("1.5"), null, null);
    EquityIndexContributionService.ContribRow down =
        new EquityIndexContributionService.ContribRow(
            1, "INFY", new BigDecimal("-0.08"), new BigDecimal("-0.9"), null, null);
    when(contributions.liveContribution(eq("NIFTY 50")))
        .thenReturn(
            new EquityIndexContributionService.IndexContribution(
                "NIFTY 50", new BigDecimal("0.04"), null, null, null, null, null,
                List.of(up, up, up), List.of(down), LocalDate.of(2026, 7, 3), true));
    BreadthController controller =
        new BreadthController(mock(BreadthService.class), contributions);

    BreadthController.LiveBreadth out = controller.live("NIFTY 50");

    assertThat(out.summary().advances()).isEqualTo(3);
    assertThat(out.summary().declines()).isEqualTo(1);
    assertThat(out.summary().total()).isEqualTo(4);
    assertThat(out.live()).isTrue();
    assertThat(out.index()).isEqualTo("NIFTY 50");
  }
}
