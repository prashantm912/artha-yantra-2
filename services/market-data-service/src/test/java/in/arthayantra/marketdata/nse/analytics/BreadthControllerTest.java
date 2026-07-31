package in.arthayantra.marketdata.nse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.servlet.GlobalExceptionHandler;
import in.arthayantra.marketdata.constituents.StaticIndexWeights;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        new BreadthController(
            mock(BreadthService.class),
            contributions,
            Clock.systemUTC(),
            mock(EquityBreadthDailyRepository.class));

    BreadthController.LiveBreadth out = controller.live("NIFTY 50");

    assertThat(out.summary().advances()).isEqualTo(3);
    assertThat(out.summary().declines()).isEqualTo(1);
    assertThat(out.summary().total()).isEqualTo(4);
    assertThat(out.live()).isTrue();
    assertThat(out.index()).isEqualTo("NIFTY 50");
  }

  /**
   * An empty {@code nse_eod_bhavcopy} (no EQ rows, task_e2e01l) makes {@code asOf()} return null;
   * {@code indexClose} used to feed that straight into {@code java.sql.Date.valueOf}, NPE-ing into
   * the catch-all 500. The fix null-guards {@code indexClose} so the fold falls through to its
   * existing empty-advances/declines 422 DATA_GAP path. Driven through MockMvc (not a direct service
   * call) so the assertion covers what {@link GlobalExceptionHandler} actually dispatches to a
   * client, not just what the service method throws.
   */
  @Test
  void liveIs422DataGapNotNpeWhenNoBhavcopyRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(LocalDate.class))).thenReturn(null); // asOf() = null
    QuoteGateway quoteGateway = mock(QuoteGateway.class);
    when(quoteGateway.quotes(any())).thenReturn(Map.of()); // no live quotes -> EOD fallback
    EquityIndexContributionService contributions =
        new EquityIndexContributionService(jdbc, new StaticIndexWeights(new ObjectMapper()), quoteGateway);
    BreadthController controller =
        new BreadthController(
            mock(BreadthService.class),
            contributions,
            Clock.systemUTC(),
            mock(EquityBreadthDailyRepository.class));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(get("/api/v1/market/breadth/live").param("index", "NIFTY 50"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }
}
