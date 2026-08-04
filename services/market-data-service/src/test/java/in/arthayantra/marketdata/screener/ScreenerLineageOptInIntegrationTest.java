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
    jdbc.update("DELETE FROM symbol_lineage WHERE predecessor_symbol = ?", PRED);
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

  /** Same negative property on the Manas screen. */
  @Test
  void manasIsAlsoUnaffectedWithoutOptIn() {
    List<String> before = manasSymbols(manas.screen(AS_OF, false));

    linkPredToSucc("ACTIVE");

    assertThat(manasSymbols(manas.screen(AS_OF, false))).isEqualTo(before);
    assertThat(manasSymbols(manas.screen(AS_OF))).isEqualTo(before);
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

  /** A WITHHELD link is inert on BOTH sides of the opt-in — the demerger escape hatch. */
  @Test
  void aWithheldLinkIsNeverStitchedEvenForAReaderThatOptedIn() {
    linkPredToSucc("WITHHELD");

    assertThat(symbolsScanned(minervini.screen(AS_OF, true))).doesNotContain(SUCC);
    assertThat(manasSymbols(manas.screen(AS_OF, true))).doesNotContain(SUCC);
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
