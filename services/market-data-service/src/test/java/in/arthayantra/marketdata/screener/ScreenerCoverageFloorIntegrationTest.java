package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.screener.manas.ManasScreenService;
import in.arthayantra.marketdata.screener.minervini.TrendTemplateService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Coverage-floor IT: the trailing-bar guard is right for the handful of names that stop printing,
 * but on a PARTIAL bhavcopy ingest it would drop most of the universe at once and publish a tiny,
 * plausible-looking screen with nothing warning. {@link ScreenCoverageFloor} refuses instead.
 *
 * <p>Fixture: 2 fresh + 3 stale = 5 eligible, 2 surviving = <b>40%</b>, against a floor configured
 * here at <b>60%</b>. {@link #fixtureStraddlesTheConfiguredFloor()} proves the fixture discriminates
 * <i>independently of the screeners</i> — it feeds the fixture's own measured counts straight into
 * the pure helper and shows 40% is refused at a 60% floor but published at a 30% one. A fixture
 * whose numbers sat on one side of every plausible threshold could not fail.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.minervini.min-current-coverage-pct=60",
      "artha.manas-arora.min-current-coverage-pct=60"
    })
@AutoConfigureMockMvc
class ScreenerCoverageFloorIntegrationTest extends MarketDataIntegrationTestBase {

  private static final List<String> FRESH = List.of("CFLRFRESH1", "CFLRFRESH2");
  private static final List<String> STALE = List.of("CFLRSTALE1", "CFLRSTALE2", "CFLRSTALE3");
  private static final int DAYS = 260;
  private static final int STALE_SESSIONS = 5;
  private static final int FLOOR_PCT = 60; // must match the property above
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TrendTemplateService minervini;
  @Autowired private ManasScreenService manas;
  @Autowired private MockMvc mockMvc;

  private void purge() {
    for (String s : allSymbols()) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
      jdbc.update("DELETE FROM manas_arora_screen_results WHERE symbol=?", s);
    }
  }

  private static List<String> allSymbols() {
    List<String> all = new ArrayList<>(FRESH);
    all.addAll(STALE);
    return all;
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    FRESH.forEach(s -> seedSeries(s, DAYS));
    STALE.forEach(s -> seedSeries(s, DAYS - STALE_SESSIONS));
  }

  private void seedSeries(String symbol, int bars) {
    List<Object[]> batch = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      double px = Math.round((100.0 + 100.0 * i / (DAYS - 1)) * 100.0) / 100.0;
      batch.add(new Object[] {AS_OF.minusDays(DAYS - 1L - i), symbol, px, px, px, 100_000L});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,close_price,high_price,low_price,"
            + "ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?) ON CONFLICT DO NOTHING",
        batch);
  }

  @Test
  void fixtureStraddlesTheConfiguredFloor() {
    // The fixture's own numbers, measured from the DB rather than assumed.
    int eligible = allSymbols().size();
    int surviving = FRESH.size();
    assertThat(countWithLastBar(AS_OF)).as("fresh names print through AS_OF").isEqualTo(surviving);
    assertThat(countWithLastBar(AS_OF.minusDays(STALE_SESSIONS)))
        .as("stale names stop STALE_SESSIONS bars earlier")
        .isEqualTo(STALE.size());

    // Discrimination proven against the PURE helper, independent of either screener: the same
    // counts are refused at the configured floor and published at a lower one. If both directions
    // agreed, the fixture could not fail whatever the screeners did.
    assertThatThrownBy(() -> ScreenCoverageFloor.check("fixture", AS_OF, surviving, eligible, FLOOR_PCT))
        .as("40%% must be REFUSED at the configured %d%% floor", FLOOR_PCT)
        .isInstanceOf(ApiException.class);
    assertThatCode(() -> ScreenCoverageFloor.check("fixture", AS_OF, surviving, eligible, 30))
        .as("the very same counts must PUBLISH at a 30% floor")
        .doesNotThrowAnyException();
  }

  @Test
  void minerviniRefusesRatherThanPublishingAGuttedScreen() {
    assertThatThrownBy(() -> minervini.screen(AS_OF))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("minervini screen REFUSED")
        .hasMessageContaining("2 of 5")
        .hasMessageContaining("Nothing was published");
  }

  @Test
  void manasRefusesRatherThanPublishingAGuttedScreen() {
    assertThatThrownBy(() -> manas.screen(AS_OF))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("manas screen REFUSED")
        .hasMessageContaining("2 of 5");
  }

  @Test
  void theRunEndpointPersistsNothingWhenTheFloorRefuses() throws Exception {
    mockMvc
        .perform(post("/api/v1/market/screener/minervini/run").param("asOf", AS_OF.toString()))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

    // The actual "refuse to PUBLISH" property: no screen row was written for the date, so the
    // previous screen date remains the latest — badged with ITS OWN date, which is truthful.
    // Scoped to this fixture's own symbols: the ITs share one DB and a sibling class seeds the same
    // AS_OF, so an unscoped count would assert on another test's rows.
    Integer written =
        jdbc.queryForObject(
            "SELECT count(*) FROM minervini_screen_results"
                + " WHERE screen_date = ?::date AND symbol LIKE 'CFLR%'",
            Integer.class,
            java.sql.Date.valueOf(AS_OF));
    assertThat(written).as("a refused screen must persist nothing").isZero();
  }

  /** How many of this fixture's symbols have {@code lastBar} as their latest printed bar. */
  private int countWithLastBar(LocalDate lastBar) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT symbol, max(trade_date) AS lb FROM nse_eod_bhavcopy"
                + " WHERE symbol LIKE 'CFLR%' GROUP BY symbol) t WHERE t.lb = ?::date",
            Integer.class,
            java.sql.Date.valueOf(lastBar));
    return n == null ? 0 : n;
  }
}
