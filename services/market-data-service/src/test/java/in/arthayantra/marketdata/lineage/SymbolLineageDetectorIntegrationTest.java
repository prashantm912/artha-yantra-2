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
import java.util.TreeMap;
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
 * pass with the rule deleted. So the two sides of this test come from two DIFFERENT signals, and
 * the expectation side never reads an NSE {@code prev_close}:
 *
 * <ul>
 *   <li><b>Input</b> — {@code lineage/bars.csv}: REAL {@code nse_eod_bhavcopy} rows exported from
 *       the live database (the first two and last two bars of every symbol involved, plus a {@code
 *       ZZCALENDAR} filler carrying one bar on each of the 276 real trading days so the session-gap
 *       arithmetic is the real arithmetic, not an artefact of a sparse fixture). Raw prices and
 *       dates only. {@code lineage/bse-bars.csv} carries the BSE identity rows — one per
 *       {@code (scrip_code, ticker)}, which is the entire signal the detector reads there.
 *   <li><b>Expectation</b> — {@code lineage/expected-pairs.csv}: 66 rows, each labelled with the
 *       ORACLE that produced its expected status, so no row's provenance is implicit.
 * </ul>
 *
 * <h2>⚠️ The gap this fixture was rebuilt to close (review MEDIUM-2)</h2>
 *
 * The first version of this fixture contained ONLY the 58 pairs BSE independently confirms, and its
 * {@code detected == expected.size()} assertion REQUIRED that exclusion. The selection criterion was
 * therefore "what the oracle already agrees with", and a rule error confined to the 8
 * BSE-unconfirmable pairs could not have failed it — a 25% defect rate hid in exactly that tier
 * (both amalgamations live there). All 66 pairs now carry bars, and the four oracle tiers are:
 *
 * <ul>
 *   <li>{@code bse-scrip} (57) — one BSE scrip_code carried both tickers. Expect ACTIVE/confirmed.
 *   <li>{@code bse-scrip-refuted} (2) — both tickers ARE on BSE under DIFFERENT scrip_codes, so BSE
 *       contradicts continuity. Expect WITHHELD/refuted. {@code CREATIVE→CNL} and {@code
 *       WORTH→WORTHPERI}; both predecessors are still printing on BSE, i.e. they never stopped.
 *   <li>{@code nse-only} (6) — neither ticker is on BSE, so nothing can be checked. Expect
 *       ACTIVE/inferred. Three are the Axis ETF rebrand, corroborated OUTSIDE this fixture by
 *       {@code marketdata.instruments} (tombstoned 2026-07-02, successors first seen 2026-07-03,
 *       same AMC); the other three ({@code GODHA→AURIGROW}, {@code QUALITY→QUALITY30}, {@code
 *       SUNDARMHLD→TSFINV}) rest on NSE price continuity ALONE and are the weakest rows here — that
 *       is a stated limitation of the data, not a hidden one.
 *   <li>{@code owner-policy} (1) — {@code TATAMOTORS→TMPV}, a demerger no signal can distinguish
 *       from a rename, seeded WITHHELD by V055. Expect WITHHELD with the migration's own reason.
 * </ul>
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
  @Autowired private SymbolLineageRepository lineageRepository;

  private List<String[]> bars;
  private List<String[]> bseBars;
  /** predecessor -> {successor, expectedStatus, oracle}. */
  private Map<String, String[]> expected;

  @BeforeEach
  void seed() {
    purge();
    bars = readCsv("/lineage/bars.csv");
    bseBars = readCsv("/lineage/bse-bars.csv");
    expected = new TreeMap<>();
    for (String[] r : readCsv("/lineage/expected-pairs.csv")) {
      expected.put(r[0], new String[] {r[1], r[2], r[3]});
    }
    jdbc.batchUpdate(
        "INSERT INTO nse_eod_bhavcopy (symbol, series, trade_date, prev_close, close_price)"
            + " VALUES (?, ?, ?::date, ?::numeric, ?::numeric)",
        bars.stream().map(r -> (Object[]) r).toList());
    jdbc.batchUpdate(
        "INSERT INTO bse_eod_bhavcopy (trade_date, scrip_code, ticker, isin)"
            + " VALUES (?::date, ?, ?, ?)",
        bseBars.stream().map(r -> (Object[]) r).toList());
  }

  @AfterEach
  void tearDown() {
    purge();
  }

  private void purge() {
    // Whole-table, deliberately — see the class javadoc. Detected lineage goes too; the WITHHELD
    // policy row seeded by V055 must NOT (it is what the owner-policy assertion reads).
    jdbc.update("DELETE FROM nse_eod_bhavcopy");
    jdbc.update("DELETE FROM bse_eod_bhavcopy");
    jdbc.update("DELETE FROM symbol_lineage WHERE source <> 'owner-policy'");
    jdbc.update(
        "UPDATE symbol_lineage SET switch_date=NULL, gap_sessions=NULL, boundary_price=NULL,"
            + " confidence=NULL, evidence=NULL WHERE source = 'owner-policy'");
  }

  /** THE test: the NSE price-continuity rule must reproduce the independently-derived pair set. */
  @Test
  void reproducesTheIndependentlyDerivedPairSet() {
    // Precondition — the fixture must actually pose the question. A silently empty or truncated
    // resource would make every assertion below vacuous. 66 pairs, ALL FOUR tiers present.
    assertThat(expected).hasSize(66);
    assertThat(bars).hasSizeGreaterThan(700);
    assertThat(bseBars).hasSizeGreaterThan(100);
    assertThat(oracleCounts())
        .containsEntry("bse-scrip", 57L)
        .containsEntry("bse-scrip-refuted", 2L)
        .containsEntry("nse-only", 6L)
        .containsEntry("owner-policy", 1L);

    SymbolLineageDetector.DetectionResult result = detector.detect();
    assertThat(result.detected()).isEqualTo(66);
    assertThat(result.asOf()).isEqualTo(LocalDate.of(2026, 8, 3));

    TreeSet<String> want = new TreeSet<>();
    expected.forEach((pred, e) -> want.add(pred + "->" + e[0]));
    assertThat(detectedPairs()).isEqualTo(want);
  }

  /** A below-span run must exercise the clipped floor rather than the table's historical floor. */
  @Test
  void belowSpanWindowUsesOwnFloorForBoundaryChecks() {
    int windowDays = 200;
    LocalDate tableFloor =
        jdbc.queryForObject("SELECT min(trade_date) FROM nse_eod_bhavcopy", LocalDate.class);
    LocalDate latest =
        jdbc.queryForObject("SELECT max(trade_date) FROM nse_eod_bhavcopy", LocalDate.class);
    assertThat(latest.toEpochDay() - tableFloor.toEpochDay()).isGreaterThan(windowDays);

    LocalDate clippedFloor = latest.minusDays(windowDays);
    List<LocalDate> sessions =
        jdbc.queryForList(
            "SELECT DISTINCT trade_date FROM nse_eod_bhavcopy ORDER BY trade_date", LocalDate.class);
    LocalDate successorDate =
        sessions.stream()
            .filter(d -> d.isAfter(clippedFloor))
            .findFirst()
            .orElseThrow();
    bar("LINCLIPP", clippedFloor, "712.0000", "712.0000");
    bar("LINCLIPS", successorDate, "712.0000", "713.0000");

    SymbolLineageDetector clippedDetector =
        new SymbolLineageDetector(jdbc, lineageRepository, windowDays, 5);
    clippedDetector.detect();

    assertThat(detectedPairs()).contains("LINCLIPP->LINCLIPS");
  }

  /**
   * Every row's STATUS matches its oracle — the assertion the old 58-pair fixture could not make,
   * because the two rows it turns on were the two it excluded.
   */
  @Test
  void everyPairLandsInTheStatusItsOracleDictates() {
    detector.detect();

    Map<String, String> gotStatus = column("status");
    Map<String, String> gotConfidence = column("confidence");
    List<String> statusMismatch = new ArrayList<>();
    List<String> confidenceMismatch = new ArrayList<>();
    expected.forEach(
        (pred, e) -> {
          String wantStatus = e[1];
          if (!wantStatus.equals(gotStatus.get(pred))) {
            statusMismatch.add(pred + " want " + wantStatus + " got " + gotStatus.get(pred));
          }
          // The owner-policy row is ALSO scrip-continuous on BSE, so it reads 'confirmed' — its
          // WITHHELD status comes from the seed, not from the evidence tier.
          String wantConfidence =
              switch (e[2]) {
                case "bse-scrip-refuted" -> "refuted";
                case "nse-only" -> "inferred";
                default -> "confirmed";
              };
          if (!wantConfidence.equals(gotConfidence.get(pred))) {
            confidenceMismatch.add(
                pred + " want " + wantConfidence + " got " + gotConfidence.get(pred));
          }
        });
    assertThat(statusMismatch).isEmpty();
    assertThat(confidenceMismatch).isEmpty();

    assertThat(gotConfidence.values().stream().filter("confirmed"::equals).count()).isEqualTo(58L);
    assertThat(gotConfidence.values().stream().filter("inferred"::equals).count()).isEqualTo(6L);
    assertThat(gotConfidence.values().stream().filter("refuted"::equals).count()).isEqualTo(2L);
  }

  /**
   * The refutation tier, on the two pairs it exists for. BSE lists BOTH tickers under DIFFERENT
   * scrip_codes — it is not silent, it CONTRADICTS — so the pair is withheld without an owner
   * ruling. Guard: an NSE-only pair, where BSE is genuinely silent, must NOT be refuted.
   */
  @Test
  void bseRefutesTheTwoAmalgamationsAndOnlyThose() {
    detector.detect();

    for (String pred : List.of("CREATIVE", "WORTH")) {
      Map<String, Object> row =
          jdbc.queryForMap(
              "SELECT status, confidence, status_reason, evidence FROM symbol_lineage"
                  + " WHERE exchange='NSE' AND predecessor_symbol = ?",
              pred);
      assertThat(row).containsEntry("status", "WITHHELD").containsEntry("confidence", "refuted");
      assertThat((String) row.get("status_reason")).contains("DIFFERENT scrip_codes");
      assertThat((String) row.get("evidence")).contains("REFUTED");
    }
    // Silence is not refutation: neither AXISNIFTY nor NIFTYAXIS is on BSE at all.
    assertThat(column("status")).containsEntry("AXISNIFTY", "ACTIVE");
    assertThat(column("confidence")).containsEntry("AXISNIFTY", "inferred");
  }

  /** A BSE ticker reused across ISINs is ambiguous and cannot permanently refute a lineage pair. */
  @Test
  void ambiguousTickerEvidenceStaysInferred() {
    List<LocalDate> cal =
        jdbc.queryForList(
            "SELECT DISTINCT trade_date FROM nse_eod_bhavcopy ORDER BY trade_date",
            LocalDate.class);
    LocalDate predecessorDate = cal.get(120);
    LocalDate successorDate = cal.get(121);
    bar("LINCOLD", predecessorDate, "701.0000", "701.0000");
    bar("LINCNEW", successorDate, "701.0000", "702.0000");
    jdbc.batchUpdate(
        "INSERT INTO bse_eod_bhavcopy (trade_date, scrip_code, ticker, isin)"
            + " VALUES (?::date, ?, ?, ?)",
        List.of(
            new Object[] {predecessorDate, "900001", "LINCOLD", "INEX11111111"},
            new Object[] {successorDate, "900002", "LINCOLD", "INEX22222222"},
            new Object[] {successorDate, "900003", "LINCNEW", "INEX33333333"}));

    detector.detect();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, confidence FROM symbol_lineage"
                + " WHERE exchange='NSE' AND predecessor_symbol = 'LINCOLD'");
    assertThat(row).containsEntry("status", "ACTIVE").containsEntry("confidence", "inferred");
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
                "SELECT count(*) FROM symbol_lineage WHERE switch_date IS NOT NULL"
                    + " AND gap_sessions <> 1",
                Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM symbol_lineage WHERE switch_date IS NOT NULL"
                    + " AND boundary_price IS NULL",
                Long.class))
        .isZero();
  }

  /**
   * An owner's WITHHELD verdict survives re-detection. {@code TATAMOTORS→TMPV} is a demerger that
   * every observable signal reports as a rename — BSE keeps the scrip AND the ISIN — so the detector
   * DOES find it and marks it {@code confirmed}. The guarantee is that it refreshes the evidence and
   * leaves the verdict, the owner's reason and the provenance alone.
   */
  @Test
  void detectionRefreshesEvidenceButNeverOverwritesAWithheldVerdict() {
    detector.detect();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT successor_symbol, status, status_reason, source, confidence, evidence,"
                + " gap_sessions FROM symbol_lineage"
                + " WHERE exchange='NSE' AND predecessor_symbol='TATAMOTORS'");
    assertThat(row)
        .containsEntry("successor_symbol", "TMPV")
        .containsEntry("status", "WITHHELD")
        .containsEntry("confidence", "confirmed")
        .containsEntry("source", "owner-policy")
        .containsEntry("gap_sessions", 1);
    assertThat((String) row.get("status_reason")).contains("Demerger");
    assertThat((String) row.get("evidence")).contains("nse prev_close continuity");
  }

  /**
   * The status ratchet turns ONE WAY only. A row that is ACTIVE today must be DEMOTED when BSE data
   * arriving later refutes it — otherwise a pair stays stitched forever on evidence that has since
   * been contradicted — and no run may ever promote a WITHHELD row back.
   */
  @Test
  void statusRatchetsToWithheldOnLaterRefutationAndNeverBack() {
    // Start with the BSE evidence for CREATIVE/CNL absent: nothing to check, so ACTIVE/inferred.
    jdbc.update("DELETE FROM bse_eod_bhavcopy WHERE ticker IN ('CREATIVE','CNL')");
    detector.detect();
    assertThat(column("status")).containsEntry("CREATIVE", "ACTIVE");
    assertThat(column("confidence")).containsEntry("CREATIVE", "inferred");

    // BSE data lands and contradicts the pair. The next run must demote it.
    jdbc.batchUpdate(
        "INSERT INTO bse_eod_bhavcopy (trade_date, scrip_code, ticker, isin)"
            + " VALUES (?::date, ?, ?, ?)",
        bseBars.stream()
            .filter(r -> "CREATIVE".equals(r[2]) || "CNL".equals(r[2]))
            .map(r -> (Object[]) r)
            .toList());
    detector.detect();
    assertThat(column("status")).containsEntry("CREATIVE", "WITHHELD");

    // …and it never comes back, even once the refuting evidence is removed again.
    jdbc.update("DELETE FROM bse_eod_bhavcopy WHERE ticker IN ('CREATIVE','CNL')");
    detector.detect();
    assertThat(column("status")).containsEntry("CREATIVE", "WITHHELD");
    assertThat(column("confidence")).containsEntry("CREATIVE", "inferred"); // evidence DOES refresh
  }

  /** Re-running changes nothing: same pairs, zero inserts the second time. */
  @Test
  void detectionIsIdempotent() {
    SymbolLineageDetector.DetectionResult first = detector.detect();
    SymbolLineageDetector.DetectionResult second = detector.detect();

    assertThat(second.detected()).isEqualTo(first.detected());
    assertThat(second.inserted()).isZero();
    assertThat(second.refreshed()).isEqualTo(second.detected());
    assertThat(second.confirmed()).isEqualTo(58);
    assertThat(second.inferred()).isEqualTo(6);
    assertThat(second.refuted()).isEqualTo(2);
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
    assertThat(detectedPairs()).hasSize(expected.size());
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

  private Map<String, Long> oracleCounts() {
    Map<String, Long> out = new TreeMap<>();
    expected.values().forEach(e -> out.merge(e[2], 1L, Long::sum));
    return out;
  }

  private TreeSet<String> detectedPairs() {
    return new TreeSet<>(
        jdbc.queryForList(
            "SELECT predecessor_symbol || '->' || successor_symbol FROM symbol_lineage"
                + " WHERE exchange = 'NSE' AND switch_date IS NOT NULL",
            String.class));
  }

  /** predecessor -> the named column, for every row the detector has touched. */
  private Map<String, String> column(String name) {
    Map<String, String> out = new TreeMap<>();
    jdbc.queryForList(
            "SELECT predecessor_symbol, "
                + name
                + " AS v FROM symbol_lineage WHERE exchange='NSE' AND switch_date IS NOT NULL")
        .forEach(r -> out.put((String) r.get("predecessor_symbol"), (String) r.get("v")));
    return out;
  }

  private int gapOf(String predecessor) {
    return jdbc.queryForObject(
        "SELECT gap_sessions FROM symbol_lineage WHERE exchange='NSE' AND predecessor_symbol = ?",
        Integer.class,
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
