package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The opt-in contract for symbol lineage (N2 / #1285), on both equity screens.
 *
 * <p><b>The property that matters most is the negative one:</b> a reader that does not opt in must
 * behave EXACTLY as before, whatever rows {@code symbol_lineage} happens to hold. "If the screen's
 * numbers move without a reader explicitly joining lineage, that is a defect." So every test here
 * measures the plain screen before AND after the lineage row exists, and requires them identical —
 * not merely plausible.
 *
 * <p><b>The fixture must DISCRIMINATE.</b> {@code LINSUCC} carries only 60 sessions of its own,
 * below the 252 gate, and {@code LINPRED} carries 210 more that end the session before it starts.
 * Neither clears the gate alone; stitched they hold 270 and the successor becomes visible. {@link
 * #fixtureActuallyDiscriminates()} pins that precondition straight against the DB so a later seed
 * change cannot leave a test that is incapable of failing. {@code LINCTRL} is a plain
 * always-eligible name, so the "unchanged" assertions compare a non-empty screen rather than two
 * empty ones.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ScreenerLineageOptInIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String PRED = "LINPRED";
  private static final String SUCC = "LINSUCC";
  private static final String CTRL = "LINCTRL";
  private static final List<String> SYMS = List.of(PRED, SUCC, CTRL);
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);
  private static final int PRED_BARS = 210;
  private static final int SUCC_BARS = 60;
  private static final int CTRL_BARS = 270;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TrendTemplateService minervini;
  @Autowired private ManasScreenService manas;

  @BeforeEach
  void seed() {
    purge();
    // One shared 100 -> 200 ramp indexed by session. CTRL walks the whole ramp; PRED walks its
    // first 210 sessions and SUCC its last 60, so PRED's last bar and SUCC's first are adjacent
    // days and the STITCHED series is bar-for-bar identical to CTRL's. That identity is what makes
    // the positive assertion airtight: if the stitch works, SUCC must pass exactly what CTRL passes.
    seedSeries(CTRL, 0, CTRL_BARS);
    seedSeries(PRED, 0, PRED_BARS);
    seedSeries(SUCC, PRED_BARS, SUCC_BARS);
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  private void purge() {
    for (String s : SYMS) {
      jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol = ?", s);
    }
    // ⚠️ EVERY row this class writes carries source='test'. Deleting only the PRED row let the
    // overlap control's CTRL->SUCC row leak into four sibling methods, which then failed for a
    // reason that had nothing to do with what they assert.
    jdbc.update("DELETE FROM symbol_lineage WHERE source = 'test'");
    jdbc.update("DELETE FROM eod_corporate_actions WHERE tradingsymbol LIKE 'LIN%'");
  }

  /**
   * Seeds {@code bars} daily bars occupying ramp positions {@code [startIndex, startIndex + bars)}
   * of one shared 270-session run rising 100 → 200. Position {@code g} is dated
   * {@code AS_OF - (269 - g)}, so two symbols on adjoining position ranges print on adjoining days
   * and their concatenation is byte-identical to a single symbol spanning both.
   */
  private void seedSeries(String symbol, int startIndex, int bars) {
    List<Object[]> batch = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      int g = startIndex + i;
      LocalDate d = AS_OF.minusDays(CTRL_BARS - 1L - g);
      double px = 100.0 + 100.0 * g / (double) (CTRL_BARS - 1);
      batch.add(new Object[] {symbol, d, px, px * 1.01, px * 0.99, px, 200_000L});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy"
            + " (symbol, series, trade_date, open_price, high_price, low_price, close_price,"
            + "  ttl_trd_qnty)"
            + " VALUES (?, 'EQ', ?, ?, ?, ?, ?, ?)",
        batch);
  }

  private void linkPredToSucc(String status) {
    jdbc.update(
        "INSERT INTO symbol_lineage (exchange, predecessor_symbol, successor_symbol, switch_date,"
            + " gap_sessions, confidence, evidence, status, source)"
            + " VALUES ('NSE', ?, ?, ?, 1, 'confirmed', 'test fixture', ?, 'test')",
        PRED, SUCC, AS_OF.minusDays(SUCC_BARS - 1L), status);
  }

  /** Precondition: neither leg clears 252 alone, and together they do. Without this the rest is vacuous. */
  @Test
  void fixtureActuallyDiscriminates() {
    assertThat(PRED_BARS).isLessThan(252);
    assertThat(SUCC_BARS).isLessThan(252);
    assertThat(PRED_BARS + SUCC_BARS).isGreaterThanOrEqualTo(252);
    assertThat(symbolsScanned(minervini.screen(AS_OF, false))).contains(CTRL).doesNotContain(SUCC);
  }

  /**
   * The negative property, on Minervini: inserting an ACTIVE lineage row must not move the plain
   * screen by so much as one symbol.
   */
  @Test
  void aReaderThatDoesNotOptInIsUnaffectedByLineageRows() {
    List<String> before = symbolsScanned(minervini.screen(AS_OF, false));
    int coverageBefore = minervini.screen(AS_OF, false).coverage();

    linkPredToSucc("ACTIVE");

    assertThat(symbolsScanned(minervini.screen(AS_OF, false))).isEqualTo(before);
    assertThat(minervini.screen(AS_OF, false).coverage()).isEqualTo(coverageBefore);
    // …and the one-arg overload every scheduler and POST /run uses is the same thing.
    assertThat(symbolsScanned(minervini.screen(AS_OF))).isEqualTo(before);
  }

  /**
   * Same negative property on the Manas screen. Asserts {@code coverage()} too, not just the LIN*
   * symbols: the symbol list alone would miss a lineage row that changed the size of the universe
   * without touching this fixture's own names, and coverage is what feeds the RS percentile.
   */
  @Test
  void manasIsAlsoUnaffectedWithoutOptIn() {
    List<String> before = manasSymbols(manas.screen(AS_OF, false));
    int coverageBefore = manas.screen(AS_OF, false).coverage();

    linkPredToSucc("ACTIVE");

    assertThat(manasSymbols(manas.screen(AS_OF, false))).isEqualTo(before);
    assertThat(manas.screen(AS_OF, false).coverage()).isEqualTo(coverageBefore);
    assertThat(manasSymbols(manas.screen(AS_OF))).isEqualTo(before);
    assertThat(manas.screen(AS_OF).coverage()).isEqualTo(coverageBefore);
  }

  /** The positive property: opting in stitches the predecessor's history onto the successor. */
  @Test
  void optingInMakesTheRenamedSuccessorVisibleOnBothScreens() {
    assertThat(symbolsScanned(minervini.screen(AS_OF, true))).doesNotContain(SUCC);
    assertThat(manasSymbols(manas.screen(AS_OF, true))).doesNotContain(SUCC);

    linkPredToSucc("ACTIVE");

    assertThat(symbolsScanned(minervini.screen(AS_OF, true))).contains(SUCC).doesNotContain(PRED);
    assertThat(manasSymbols(manas.screen(AS_OF, true))).contains(SUCC).doesNotContain(PRED);
  }

  /**
   * ⚠️ MEDIUM-1: a link that had not switched yet AS OF the screen date must not be walked.
   * {@code lineageImpact} takes {@code asOf} from the query string, so without this guard a replay
   * dated before a rename relabels the predecessor's bars onto a ticker that did not exist yet.
   * Measured on live data: 23 of the 65 active links switch after 2026-01-31, so this is the common
   * case on any replay, not an edge.
   *
   * <p>Asserted on the BASE CTE rather than through {@code screen()} deliberately: this fixture
   * holds 270 bars, so at any earlier {@code asOf} nothing clears the 252-session gate and BOTH
   * sides of a screen-level comparison would be empty — a test that cannot fail.
   */
  @Test
  void aLinkThatHasNotSwitchedYetIsNotWalkedOnAHistoricalReplay() {
    linkPredToSucc("ACTIVE");
    LocalDate switchDate = AS_OF.minusDays(SUCC_BARS - 1L);
    LocalDate beforeSwitch = switchDate.minusDays(1);

    // As of the switch date the link applies: PRED's bars are relabelled onto SUCC and PRED is gone.
    assertThat(baseSymbolCount(AS_OF, SUCC)).isEqualTo(CTRL_BARS);
    assertThat(baseSymbolCount(AS_OF, PRED)).isZero();

    // One day earlier the rename has not happened. PRED must still be PRED, and nothing may be
    // labelled SUCC at all — SUCC has no bars of its own before its own first print.
    assertThat(baseSymbolCount(beforeSwitch, PRED)).isEqualTo(PRED_BARS);
    assertThat(baseSymbolCount(beforeSwitch, SUCC)).isZero();
  }

  /**
   * ⚠️ HIGH-3: a corporate action dated AFTER the switch is recorded under the SUCCESSOR ticker, so
   * a CA lateral keyed on the raw symbol leaves the predecessor's bars unadjusted and opens a price
   * cliff at the join — the exact audit-H6 defect {@code AdjustedEquityDailySql} exists to prevent.
   * Measured live at the {@code AARVEEDEN→VGL} join: 151.4200 beside 73.5250, a ~51% one-day gap
   * feeding SMA200, both 52-week extremes and all four RS legs.
   *
   * <p>The fixture puts a 0.5 SPLIT on the SUCCESSOR after the switch and asserts the stitched
   * series has no step at the boundary: with the lateral lineage-resolved, EVERY bar in the chain
   * carries the 0.5 factor, so the ratio between the last pre-switch bar and the first post-switch
   * bar is the same as it is on the unsplit control.
   */
  @Test
  void aPostSwitchCorporateActionAdjustsThePredecessorSideToo() {
    linkPredToSucc("ACTIVE");
    jdbc.update(
        "INSERT INTO eod_corporate_actions (exchange, tradingsymbol, ex_date, ratio, kind)"
            + " VALUES ('NSE', ?, ?, 0.5, 'SPLIT')",
        SUCC, AS_OF.minusDays(10));

    List<java.math.BigDecimal> closes = stitchedCloses();
    assertThat(closes).hasSize(2);
    java.math.BigDecimal lastPreSwitch = closes.get(0);
    java.math.BigDecimal firstPostSwitch = closes.get(1);

    // Both sides adjusted by the same 0.5: the series steps by one ramp increment, not by 2x.
    java.math.BigDecimal ratio =
        firstPostSwitch.divide(lastPreSwitch, 4, java.math.RoundingMode.HALF_UP);
    assertThat(ratio).isBetween(new java.math.BigDecimal("0.99"), new java.math.BigDecimal("1.02"));
    // Red-proof anchor: keyed on the raw symbol this ratio is ~0.5. Assert the cliff is absent.
    assertThat(ratio).isGreaterThan(new java.math.BigDecimal("0.90"));
  }

  /**
   * ⚠️ LOW-3: a hand-written pair whose bars OVERLAP must not emit duplicate {@code (symbol,
   * bucket)} rows — that silently corrupts every window function downstream with no error anywhere.
   * The {@code bucket < switch_date} bar guard makes it structurally impossible; this pins it,
   * because the escape hatch invites hand-written rows.
   */
  @Test
  void anOverlappingHandWrittenPairCannotDuplicateBars() {
    // Claim a switch date in the MIDDLE of the predecessor's own run, so the two series overlap.
    jdbc.update(
        "INSERT INTO symbol_lineage (exchange, predecessor_symbol, successor_symbol, switch_date,"
            + " gap_sessions, confidence, evidence, status, source)"
            + " VALUES ('NSE', ?, ?, ?, 1, 'inferred', 'overlap control', 'ACTIVE', 'test')",
        CTRL, SUCC, AS_OF.minusDays(30));

    Integer dupes =
        jdbc.queryForObject(
            "SELECT count(*) FROM (SELECT symbol, bucket FROM ("
                + in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql.screenerBaseCte(true)
                + ") q WHERE symbol LIKE 'LIN%' GROUP BY 1,2 HAVING count(*) > 1) d",
            Integer.class,
            java.sql.Date.valueOf(AS_OF),
            java.sql.Date.valueOf(AS_OF),
            java.sql.Date.valueOf(AS_OF));
    assertThat(dupes).isZero();
  }

  /** A WITHHELD link is inert on BOTH sides of the opt-in — the demerger escape hatch. */
  @Test
  void aWithheldLinkIsNeverStitchedEvenForAReaderThatOptedIn() {
    linkPredToSucc("WITHHELD");

    assertThat(symbolsScanned(minervini.screen(AS_OF, true))).doesNotContain(SUCC);
    assertThat(manasSymbols(manas.screen(AS_OF, true))).doesNotContain(SUCC);
  }

  /**
   * The stitched close of the LAST pre-switch bar and the FIRST post-switch bar, read straight off
   * the lineage-expanded base CTE — the plane the screen actually reads, not a re-derivation.
   */
  private List<java.math.BigDecimal> stitchedCloses() {
    return jdbc.queryForList(
        "SELECT close FROM ("
            + in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql.screenerBaseCte(true)
            + ") q WHERE symbol = ? AND bucket IN (?, ?) ORDER BY bucket",
        java.math.BigDecimal.class,
        java.sql.Date.valueOf(AS_OF),
        java.sql.Date.valueOf(AS_OF),
        java.sql.Date.valueOf(AS_OF),
        SUCC,
        java.sql.Date.valueOf(AS_OF.minusDays(SUCC_BARS)),
        java.sql.Date.valueOf(AS_OF.minusDays(SUCC_BARS - 1L)));
  }

  /** How many bars the lineage-expanded base CTE labels {@code symbol}, as of {@code asOf}. */
  private int baseSymbolCount(LocalDate asOf, String symbol) {
    java.sql.Date d = java.sql.Date.valueOf(asOf);
    return jdbc.queryForObject(
        "SELECT count(*) FROM ("
            + in.arthayantra.marketdata.equitydaily.AdjustedEquityDailySql.screenerBaseCte(true)
            + ") q WHERE symbol = ?",
        Integer.class,
        d, d, d, symbol);
  }

  private static List<String> symbolsScanned(TrendTemplateService.ScreenResult r) {
    return r.candidates().stream().map(TrendCandidate::symbol).filter(s -> s.startsWith("LIN")).sorted().toList();
  }

  private static List<String> manasSymbols(ManasScreenService.ScreenResult r) {
    return r.candidates().stream()
        .map(in.arthayantra.marketdata.screener.manas.ManasCandidate::symbol)
        .filter(s -> s.startsWith("LIN"))
        .sorted()
        .toList();
  }
}
