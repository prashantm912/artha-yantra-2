package in.arthayantra.marketdata.lineage;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The derivation test for {@link SymbolLineageDetector} — N2 / #1285.
 *
 * <h2>Why it is built this way</h2>
 *
 * A seed test that hand-builds the pairs it asserts proves nothing about the derivation: it would
 * pass with the rule deleted. So the two sides of this test come from two DIFFERENT, independent
 * signals, and neither is the rule under test:
 *
 * <ul>
 *   <li><b>Input</b> — {@code lineage/bars.csv}: REAL {@code nse_eod_bhavcopy} rows exported from
 *       the live database (the first two and last two bars of every symbol involved, plus a
 *       {@code ZZCALENDAR} filler carrying one bar on each of the 276 real trading days so the
 *       session-gap arithmetic is the real arithmetic, not an artefact of a sparse fixture). Raw
 *       prices and dates only — no pairing information of any kind.
 *   <li><b>Expectation</b> — {@code lineage/expected-pairs.csv}: derived entirely from BSE, where
 *       {@code scrip_code} is a stable per-listing identifier, so a rename is directly observable
 *       as ONE scrip_code carrying TWO tickers. It never looks at an NSE {@code prev_close}.
 * </ul>
 *
 * The detector reads NSE price continuity. If its rule is wrong, it cannot reproduce a BSE-derived
 * answer, and this test fails. The filler symbol is structurally inert: it trades on every date, so
 * it can never be a predecessor (it never stops) nor a successor (its first bar IS the data floor).
 *
 * <p>The synthetic controls at the bottom are the opposite case and are legitimately hand-built:
 * they assert what the rule REFUSES, which is the direction this rule is deliberately biased in.
 *
 * <p>The whole EOD table is purged around each method. The rule's calendar is {@code SELECT DISTINCT
 * trade_date} over the entire table, so a stray row from a sibling IT would move every session gap;
 * every other bhavcopy IT seeds in its own {@code @BeforeEach}, so this is safe and it is the only
 * way the gap assertions can be deterministic.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class SymbolLineageDetectorIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String CAL = "ZZCALENDAR";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private SymbolLineageDetector detector;

  private List<String[]> bars;
  private List<String[]> expectedPairs;

  @BeforeEach
  void seed() {
    purge();
    bars = readCsv("/lineage/bars.csv");
    expectedPairs = readCsv("/lineage/expected-pairs.csv");
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy (symbol, series, trade_date, prev_close, close_price)"
            + " VALUES (?, ?, ?::date, ?::numeric, ?::numeric)",
        bars.stream().map(r -> (Object[]) r).toList());
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  private void purge() {
    // Whole-table, deliberately — see the class javadoc. Detected lineage goes too; the WITHHELD
    // policy row seeded by V054 must NOT (it is what the withheld assertion reads).
    jdbc.update("DELETE FROM nse_eod_bhavcopy");
    jdbc.update("DELETE FROM bse_eod_bhavcopy");
    jdbc.update("DELETE FROM symbol_lineage WHERE source <> 'owner-policy'");
    jdbc.update(
        "UPDATE symbol_lineage SET switch_date=NULL, gap_sessions=NULL, boundary_price=NULL,"
            + " confidence=NULL, evidence=NULL WHERE source = 'owner-policy'");
  }

  /** THE test: the NSE price-continuity rule must reproduce the BSE-derived pair set exactly. */
  @Test
  void reproducesTheIndependentlyDerivedPairSet() {
    // Precondition — the fixture must actually pose the question. A silently empty or truncated
    // resource would make every assertion below vacuous.
    assertThat(expectedPairs).hasSizeGreaterThan(50);
    assertThat(bars).hasSizeGreaterThan(700);

    SymbolLineageDetector.DetectionResult result = detector.detect();
    assertThat(result.detected()).isEqualTo(expectedPairs.size());
    assertThat(result.asOf()).isEqualTo(LocalDate.of(2026, 8, 3));

    TreeSet<String> expected = new TreeSet<>();
    for (String[] p : expectedPairs) {
      expected.add(p[0] + "->" + p[1]);
    }
    assertThat(detectedPairs()).isEqualTo(expected);
  }

  /**
   * Session-gap arithmetic, pinned against a fact established from the trading calendar alone:
   * {@code YAARI} last printed 2025-11-03 and {@code IBULLSLTD} first printed 2025-11-10 with
   * exactly 3 NSE sessions in between, so its gap is 4 — the single pair in the whole set that is
   * not adjacent. Everything else must read 1.
   */
  @Test
  void gapSessionsAreRealSessionCountsNotCalendarDays() {
    detector.detect();
    assertThat(gapOf("YAARI")).isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM symbol_lineage WHERE source = 'nse-price-continuity'"
                    + " AND gap_sessions <> 1",
                Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM symbol_lineage WHERE boundary_price IS NULL"
                    + " AND source = 'nse-price-continuity'",
                Long.class))
        .isZero();
  }

  /**
   * With no BSE rows present every link is {@code inferred}; adding ONE scrip_code that carried both
   * tickers flips exactly that link to {@code confirmed}. Proves the corroboration is a real second
   * signal rather than a constant.
   */
  @Test
  void bseScripContinuityIsWhatUpgradesConfidence() {
    detector.detect();
    assertThat(confidenceOf("GUJGASLTD")).isEqualTo("inferred");

    jdbc.update(
        "INSERT INTO bse_eod_bhavcopy (trade_date, scrip_code, ticker, isin)"
            + " VALUES (date '2026-06-30', '500000', 'GUJGASLTD', 'INEBSE000001'),"
            + "        (date '2026-07-01', '500000', 'GUJENERGY', 'INEBSE000001')");
    detector.detect();

    assertThat(confidenceOf("GUJGASLTD")).isEqualTo("confirmed");
    assertThat(evidenceOf("GUJGASLTD")).contains("bse scrip_code carried both tickers");
    assertThat(confidenceOf("SELAN")).isEqualTo("inferred"); // untouched by the one BSE scrip
  }

  /**
   * An owner's WITHHELD verdict survives re-detection. {@code TATAMOTORS→TMPV} is a demerger that
   * every observable signal reports as a rename, so the detector DOES find it — the guarantee is
   * that it refreshes the evidence and leaves the verdict and the reason alone.
   */
  @Test
  void detectionRefreshesEvidenceButNeverOverwritesAWithheldVerdict() {
    detector.detect();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT successor_symbol, status, status_reason, source, confidence, evidence,"
                + " gap_sessions FROM symbol_lineage"
                + " WHERE exchange='NSE' AND predecessor_symbol='TATAMOTORS'");
    assertThat(row).containsEntry("successor_symbol", "TMPV").containsEntry("status", "WITHHELD");
    assertThat((String) row.get("status_reason")).contains("Demerger");
    assertThat(row).containsEntry("source", "owner-policy"); // provenance survives too
    assertThat((String) row.get("evidence")).contains("nse prev_close continuity");
    assertThat(row).containsEntry("gap_sessions", 1);
  }

  /** Re-running changes nothing: same pairs, zero inserts the second time. */
  @Test
  void detectionIsIdempotent() {
    SymbolLineageDetector.DetectionResult first = detector.detect();
    SymbolLineageDetector.DetectionResult second = detector.detect();

    assertThat(second.detected()).isEqualTo(first.detected());
    assertThat(second.inserted()).isZero();
    assertThat(second.refreshed()).isEqualTo(second.detected());
  }

  /**
   * The four refusals, in the direction this rule is deliberately biased. Each control is a real
   * shape the live tape produces, and a wrong pair here would merge two unrelated companies' price
   * histories into an owner-facing screen — which is why every ambiguity resolves to DROP.
   */
  @Test
  void refusesTheFourShapesThatWouldProduceAWrongPair() {
    List<LocalDate> cal =
        jdbc.queryForList(
            "SELECT DISTINCT trade_date FROM nse_eod_bhavcopy ORDER BY trade_date",
            LocalDate.class);
    LocalDate mid = cal.get(120);
    LocalDate next = cal.get(121);
    LocalDate farAway = cal.get(140); // 20 sessions on — far past the gap cap of 5
    LocalDate last = cal.get(cal.size() - 1);

    // (1) prev_close off by one tick at the stored 4dp.
    bar("LINMISSP", cal.get(119), "500.0000", "500.0000");
    bar("LINMISSP", mid, "500.0000", "500.0000");
    bar("LINMISSS", next, "500.0001", "501.0000");
    // (2) succession gap wider than the cap (a long suspension across the switch).
    bar("LINGAPP", mid, "410.0000", "410.0000");
    bar("LINGAPS", farAway, "410.0000", "411.0000");
    // (3) ambiguity: two predecessors stop on the same date at the same close.
    bar("LINAMBP1", mid, "333.3300", "333.3300");
    bar("LINAMBP2", mid, "333.3300", "333.3300");
    bar("LINAMBS", next, "333.3300", "334.0000");
    // (4) overlap: the "predecessor" never stops, so it is not a predecessor at all.
    bar("LINOVLP", mid, "220.0000", "220.0000");
    bar("LINOVLP", last, "220.0000", "220.0000");
    bar("LINOVLS", next, "220.0000", "221.0000");

    detector.detect();

    assertThat(detectedPairs())
        .noneMatch(p -> p.startsWith("LIN"))
        .noneMatch(p -> p.contains("->LIN"));
    // …and the fixture's real pairs are all still there, so the controls did not simply break the
    // run. Without this the test would pass with the detector returning nothing at all.
    assertThat(detectedPairs()).hasSize(expectedPairs.size());
  }

  /** The calendar filler cannot be mistaken for either side of a link. */
  @Test
  void theCalendarFillerIsStructurallyInert() {
    detector.detect();
    assertThat(detectedPairs()).noneMatch(p -> p.contains(CAL));
  }

  // ---- helpers -------------------------------------------------------------------------------

  private void bar(String symbol, LocalDate d, String prevClose, String close) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (symbol, series, trade_date, prev_close, close_price)"
            + " VALUES (?, 'EQ', ?, ?::numeric, ?::numeric)",
        symbol, d, prevClose, close);
  }

  private TreeSet<String> detectedPairs() {
    return new TreeSet<>(
        jdbc.queryForList(
            "SELECT predecessor_symbol || '->' || successor_symbol FROM symbol_lineage"
                + " WHERE exchange = 'NSE' AND switch_date IS NOT NULL",
            String.class));
  }

  private int gapOf(String predecessor) {
    return jdbc.queryForObject(
        "SELECT gap_sessions FROM symbol_lineage WHERE exchange='NSE' AND predecessor_symbol = ?",
        Integer.class,
        predecessor);
  }

  private String confidenceOf(String predecessor) {
    return jdbc.queryForObject(
        "SELECT confidence FROM symbol_lineage WHERE exchange='NSE' AND predecessor_symbol = ?",
        String.class,
        predecessor);
  }

  private String evidenceOf(String predecessor) {
    return jdbc.queryForObject(
        "SELECT evidence FROM symbol_lineage WHERE exchange='NSE' AND predecessor_symbol = ?",
        String.class,
        predecessor);
  }

  /** Header-skipping CSV read. Tolerates a CRLF checkout — {@code *.csv} is not pinned to LF. */
  private static List<String[]> readCsv(String resource) {
    List<String[]> rows = new ArrayList<>();
    try (InputStream in = SymbolLineageDetectorIntegrationTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("fixture not on the classpath: " + resource);
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      r.readLine(); // header
      String line;
      while ((line = r.readLine()) != null) {
        String trimmed = line.strip();
        if (!trimmed.isEmpty()) {
          rows.add(trimmed.split(",", -1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }
}
