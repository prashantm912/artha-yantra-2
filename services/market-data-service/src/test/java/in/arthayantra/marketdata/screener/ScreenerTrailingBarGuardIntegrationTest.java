package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.screener.manas.ManasCandidate;
import in.arthayantra.marketdata.screener.manas.ManasScreenService;
import in.arthayantra.marketdata.screener.minervini.TrendCandidate;
import in.arthayantra.marketdata.screener.minervini.TrendTemplateService;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Trailing-bar guard IT for BOTH equity screeners. The rn=1 row selector picks each symbol's OWN
 * latest bar — whatever date that is — while the result is labelled with {@code latestScreenDate()}
 * = the UNIVERSE's max trade_date. A symbol that stopped printing (delisted / renamed / suspended)
 * therefore emitted a full candidate row computed on a weeks-old close under today's badge, with
 * the staleness neither carried on the row nor visible in the response. Measured live on
 * 2026-08-03: 12 of 2274 Manas rows and 9 of 1776 Minervini rows were stale, and JBCHEPHARM sat in
 * the persisted Minervini screen with {@code passes_all = true} on a 12-session-old close.
 *
 * <p><b>The fixture must DISCRIMINATE.</b> Two symbols with an IDENTICAL price shape — one printing
 * through {@code AS_OF}, one whose tape stops {@code STALE_SESSIONS} bars earlier and otherwise
 * clears every gate. Without the guard both are scanned and emitted; with it only the fresh one
 * survives. {@link #fixtureActuallyDiscriminates()} pins that precondition directly against the DB,
 * so a later seed change that made the guarded and unguarded queries agree would fail loudly rather
 * than leave a test that cannot fail.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      // Floor DISABLED here on purpose: this fixture is 1 fresh + 1 stale = 50% surviving, which the
      // coverage floor would (correctly) refuse, masking what this class is about. The guard and the
      // floor are separate behaviours and get separate fixtures — the floor's own refusal is proven
      // in ScreenerCoverageFloorIntegrationTest.
      "artha.minervini.min-current-coverage-pct=0",
      "artha.manas-arora.min-current-coverage-pct=0"
    })
class ScreenerTrailingBarGuardIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String FRESH = "TBGFRESH"; // prints through AS_OF
  private static final String STALE = "TBGSTALE"; // identical shape, tape stops early
  private static final List<String> SYMS = List.of(FRESH, STALE);
  private static final int DAYS = 260;
  // 255 remaining bars still clears the 252-session floor, so the ONLY thing separating STALE from
  // FRESH is the trailing-bar guard itself — not the sessions gate.
  private static final int STALE_SESSIONS = 5;
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TrendTemplateService minervini;
  @Autowired private ManasScreenService manas;

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol=?", s);
      jdbc.update("DELETE FROM minervini_screen_results WHERE symbol=?", s);
      jdbc.update("DELETE FROM manas_arora_screen_results WHERE symbol=?", s);
    }
  }

  // The ITs share a singleton DB with no per-method cleanup — purge BOTH before (isolation from a
  // prior run) and after (so these rows never leak into a sibling that counts the universe, e.g.
  // MinerviniScreenerIntegrationTest's coverage==3 assertion).
  @AfterEach
  void tearDown() {
    purge();
  }

  @BeforeEach
  void seed() {
    purge();
    // Clean rising uptrend 100 -> 200: clears the price floor, the MA stack and the turnover gate.
    seedSeries(FRESH, DAYS);
    seedSeries(STALE, DAYS - STALE_SESSIONS);
  }

  /** Seeds {@code bars} consecutive daily bars ending {@code DAYS-bars} days before {@code AS_OF}. */
  private void seedSeries(String symbol, int bars) {
    List<Object[]> batch = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      // Identical shape for both names: bar i carries the same price regardless of series length,
      // so STALE differs from FRESH ONLY by its missing trailing bars.
      double px = Math.round((100.0 + 100.0 * i / (DAYS - 1)) * 100.0) / 100.0;
      LocalDate day = AS_OF.minusDays(DAYS - 1L - i);
      batch.add(new Object[] {day, symbol, px, px, px, 100_000L});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy(trade_date,symbol,series,close_price,high_price,low_price,"
            + "ttl_trd_qnty) VALUES(?,?, 'EQ', ?,?,?,?) ON CONFLICT DO NOTHING",
        batch);
  }

  @Test
  void fixtureActuallyDiscriminates() {
    // Both names clear the 252-session floor, so neither is removed by the sessions gate...
    assertThat(sessions(FRESH)).as("fresh session count").isGreaterThanOrEqualTo(252);
    assertThat(sessions(STALE)).as("stale session count still clears the floor")
        .isGreaterThanOrEqualTo(252);
    // ...and the ONLY difference is that STALE's latest bar trails the universe's latest bar.
    assertThat(lastBar(FRESH)).as("fresh prints through the screen date").isEqualTo(AS_OF);
    assertThat(lastBar(STALE)).as("stale stops STALE_SESSIONS bars earlier")
        .isEqualTo(AS_OF.minusDays(STALE_SESSIONS));
    assertThat(lastBar(STALE)).as("guarded and unguarded queries must disagree on STALE")
        .isBefore(lastBar(FRESH));
  }

  @Test
  void minerviniDropsTheSymbolWhoseLatestBarTrailsTheUniverse() {
    TrendTemplateService.ScreenResult res = minervini.screen(AS_OF);
    assertThat(res.screenDate()).isEqualTo(AS_OF);
    List<String> symbols = res.candidates().stream().map(TrendCandidate::symbol).toList();
    assertThat(symbols).as("a symbol printing through the screen date is still screened")
        .contains(FRESH);
    assertThat(symbols)
        .as("a symbol %d sessions stale must NOT emit a row badged with the universe asOf",
            STALE_SESSIONS)
        .doesNotContain(STALE);
  }

  @Test
  void manasDropsTheSymbolWhoseLatestBarTrailsTheUniverse() {
    ManasScreenService.ScreenResult res = manas.screen(AS_OF);
    assertThat(res.screenDate()).isEqualTo(AS_OF);
    List<String> symbols = res.candidates().stream().map(ManasCandidate::symbol).toList();
    assertThat(symbols).as("a symbol printing through the screen date is still screened")
        .contains(FRESH);
    assertThat(symbols)
        .as("a symbol %d sessions stale must NOT emit a row badged with the universe asOf",
            STALE_SESSIONS)
        .doesNotContain(STALE);
  }

  private int sessions(String symbol) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM nse_eod_bhavcopy WHERE symbol=? AND series IN ('EQ','BE')"
                + " AND trade_date <= ?::date AND trade_date > (?::date - 420)",
            Integer.class,
            symbol,
            java.sql.Date.valueOf(AS_OF),
            java.sql.Date.valueOf(AS_OF));
    return n == null ? 0 : n;
  }

  private LocalDate lastBar(String symbol) {
    return jdbc.queryForObject(
        "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE symbol=?", LocalDate.class, symbol);
  }
}
