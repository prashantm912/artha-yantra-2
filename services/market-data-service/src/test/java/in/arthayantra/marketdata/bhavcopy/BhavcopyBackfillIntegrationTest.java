package in.arthayantra.marketdata.bhavcopy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.bse.BseBhavcopyFetcher;
import in.arthayantra.marketdata.bse.BseCorporateActionFetcher;
import in.arthayantra.marketdata.bse.BseEodBhavcopyRepository;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.EodCorporateActionRepository;
import in.arthayantra.marketdata.candles.EquitySplitBonusAdjuster;
import in.arthayantra.marketdata.dividends.DividendRepository;
import in.arthayantra.marketdata.nse.BhavcopyFetcher;
import in.arthayantra.marketdata.nse.LiveBhavcopyFetcher;
import in.arthayantra.marketdata.nse.NseCorporateActionFetcher;
import in.arthayantra.marketdata.nse.NseEodBhavcopyRepository;
import in.arthayantra.marketdata.nse.NseHttpClient;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * End-to-end EOD bhavcopy backfill against the real Timescale container: watermark catch-up,
 * EQ/BE projection into candles, DO-NOTHING vs a Kite-owned bar, GS-series exclusion, BSE projection,
 * NSE→BSE ratio cross-map by ISIN, read-time split/bonus adjustment, and the CorporateActionJob gate.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      // The context's own startup catch-up is FIRE-AND-FORGET (BhavcopyBackfillService.runIfFree
      // submits to its own executor and returns), so its write to nse_eod_bhavcopy outlives
      // ApplicationReadyEvent and raced this class's whole-table DELETE — deadlock detected, and
      // ONLY on the 2-core CI runner, which is why it passed every PR shard while turning
      // push:main red from #1016 onward. Same switch-off precedent as bootstrap-sync above.
      "artha.bhavcopy.startup-catchup=false"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BhavcopyBackfillIntegrationTest extends MarketDataIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-18T06:30:00Z"), ZoneOffset.UTC);
  private static final LocalDate D1 = LocalDate.of(2026, 6, 16); // Tue
  private static final LocalDate D2 = LocalDate.of(2026, 6, 17); // Wed (ex-date)

  @Autowired CandleRepository candles;
  @Autowired NseEodBhavcopyRepository nseRepo;
  @Autowired BseEodBhavcopyRepository bseRepo;
  @Autowired EodCorporateActionRepository caRepo;
  @Autowired DividendRepository dividendRepo;
  @Autowired in.arthayantra.marketdata.lineage.SymbolRenameEventRepository renameRepo;
  @Autowired EquitySplitBonusAdjuster adjuster;
  @Autowired in.arthayantra.marketdata.ingest.IngestRunLedger ledger;
  @Autowired JdbcTemplate jdbc;

  /** Stubs the NSE archive host for the two tests that drive the REAL {@link LiveBhavcopyFetcher}. */
  private static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @Test
  @Order(1)
  void fullBackfillProjectionRatiosAndAdjustment() {
    cleanupOwnRows();

    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            nseStub(), nseRepo, bseStub(), bseRepo, caStub(), bseCaStub(), caRepo, dividendRepo, renameRepo,
            candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10, 90, 7, 420);

    // A Kite-owned 1d bar must survive the bhavcopy projection (DO NOTHING; source not in PK).
    candles.upsertAuthoritativeAll(
        List.of(new Candle("NSE", "UNIVA", "1d", bucket(D1),
            bd("999"), bd("999"), bd("999"), bd("999"), 1L, null, "KITE")));

    svc.runNse();
    svc.runBse();
    svc.runRatios();

    // --- NSE projection + DO NOTHING + series filter ---
    assertThat(close("NSE", "UNIVA", D1)).isEqualByComparingTo("999"); // Kite bar untouched
    assertThat(source("NSE", "UNIVA", D1)).isEqualTo("KITE");
    assertThat(close("NSE", "UNIVA", D2)).isEqualByComparingTo("114"); // BHAVCOPY filled where free
    assertThat(source("NSE", "UNIVA", D2)).isEqualTo("BHAVCOPY");
    assertThat(close("NSE", "UNIVB", D2)).isEqualByComparingTo("51"); // BE series projected
    assertThat(candles.range("NSE", "UNIVG", "1d", bucket(D2), bucket(D2).plusDays(1))).isEmpty(); // GS not projected
    assertThat(nseRepo.maxTradeDate()).isAfterOrEqualTo(D2); // watermark reflects this backfill
    // GS row is still stored raw even though it is not a candle.
    assertThat(rawNseCount("UNIVG")).isEqualTo(1);

    // --- BSE projection ---
    assertThat(close("BSE", "UNIVBSE", D2)).isEqualByComparingTo("204");
    assertThat(bseRepo.maxTradeDate()).isEqualTo(D2);

    // --- ratios: NSE keyed + BSE cross-mapped by ISIN ---
    assertThat(caRepo.ratiosFor("NSE", "UNIVADJ")).singleElement()
        .satisfies(r -> {
          assertThat(r.exDate()).isEqualTo(D2);
          assertThat(r.ratio()).isEqualByComparingTo("0.5"); // Bonus 1:1
        });
    assertThat(caRepo.ratiosFor("BSE", "UNIVBSE")).hasSize(1); // INETEST00001 → BSE listing
    // BSE-only scrip (no NSE twin) gets its ratio directly from the BSE corporate-actions feed
    assertThat(caRepo.ratiosFor("BSE", "UNIVBSEONLY"))
        .singleElement()
        .satisfies(r -> assertThat(r.ratio()).isEqualByComparingTo("0.5")); // Bonus 1:1

    // --- read-time adjustment: pre-ex BHAVCOPY bar scaled, ex-date bar unchanged ---
    List<Candle> adjusted =
        adjuster.adjust(
            "NSE", "UNIVADJ",
            candles.range("NSE", "UNIVADJ", "1d", bucket(D1), bucket(D2).plusDays(1)));
    assertThat(adjusted.get(0).close()).isEqualByComparingTo("100.0000"); // 200 × 0.5
    assertThat(adjusted.get(1).close()).isEqualByComparingTo("100"); // ex-date, unchanged

    // --- CorporateActionJob gate: Kite-touched symbol swept, BHAVCOPY-only skipped ---
    assertThat(candles.hasNonBhavcopyDaily("NSE", "UNIVA")).isTrue(); // has a KITE 1d bar
    assertThat(candles.hasNonBhavcopyDaily("NSE", "UNIVB")).isFalse(); // BHAVCOPY-only
  }

  @Test
  @Order(2)
  void aTransientlyMissedDayIsReProbedAndHeals() {
    jdbc.update("DELETE FROM candles WHERE tradingsymbol LIKE 'TRZ%'");
    jdbc.update("DELETE FROM nse_eod_bhavcopy");

    LocalDate trd1 = LocalDate.of(2026, 6, 16);
    LocalDate trd2 = LocalDate.of(2026, 6, 17);
    AtomicBoolean cleared = new AtomicBoolean(false); // trd2's fetch "fails" (empty) until set

    BhavcopyFetcher flaky =
        new BhavcopyFetcher() {
          @Override
          public List<BhavcopyRow> fetchLatest() {
            return List.of();
          }

          @Override
          public List<BhavcopyRow> fetchForDate(LocalDate date) {
            if (date.equals(trd1)) {
              return List.of(nseRow(trd1, "TRZ", "EQ", "10", "11", "9", "10", 100L));
            }
            if (date.equals(trd2) && cleared.get()) {
              return List.of(nseRow(trd2, "TRZ", "EQ", "12", "13", "11", "12", 100L));
            }
            return List.of(); // trd2 before 'cleared' reads as a holiday (transient miss)
          }
        };
    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            flaky, nseRepo, emptyBse(), bseRepo, emptyCa(), emptyBseCa(), caRepo, dividendRepo, renameRepo,
            candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10, 90, 7, 420);

    // Run 1: trd2 missed; the watermark must NOT advance past it.
    svc.runNse();
    assertThat(nseRepo.maxTradeDate()).isEqualTo(trd1);
    assertThat(close("NSE", "TRZ", trd2)).isNull();

    // Run 2: the transient clears; the anti-join re-probes trd2 (below the watermark) and heals it.
    cleared.set(true);
    svc.runNse();
    assertThat(close("NSE", "TRZ", trd2)).isEqualByComparingTo("12");
    assertThat(nseRepo.maxTradeDate()).isEqualTo(trd2);
  }

  @Test
  @Order(3)
  void aCompletedRunPublishesBhavcopyBackfillCompleted() {
    // Pins the PRODUCER half of the audit-H1 fix: the screens chain off this event, so a run that
    // finishes without publishing silently reverts the system to fallback-crons-only. Publication
    // must happen even for a no-new-days run (holiday/outage) — the listeners' watermark guard
    // makes that a cheap no-op, but the wiring must fire.
    List<Object> published = new java.util.concurrent.CopyOnWriteArrayList<>();
    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            emptyNse(), nseRepo, emptyBse(), bseRepo, emptyCa(), emptyBseCa(), caRepo,
            dividendRepo, renameRepo, candles, CLOCK, published::add, noopNtfy(), ledger, "EQ,BE", 10, 90, 7,
            420);

    svc.runIfFree(); // the scheduler/startup entry — submits runLocked to the service's executor

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(published)
                    .filteredOn(e -> e instanceof BhavcopyBackfillCompleted)
                    .hasSize(1));
  }

  @Test
  @Order(4)
  void aBhavcopyRunIsRecordedInTheIngestLedger() {
    // A4: the REAL bhavcopy job wired end-to-end — runLocked (via runIfFree) opens a BHAVCOPY row and
    // stamps its terminal status. A deterministic no-fetch run (0 rows); exact-count semantics are
    // pinned in IngestRunLedgerIntegrationTest — here we only prove the real backfill records a run.
    jdbc.update("DELETE FROM ingest_runs WHERE source = 'BHAVCOPY'");

    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            emptyNse(), nseRepo, emptyBse(), bseRepo, emptyCa(), emptyBseCa(), caRepo,
            dividendRepo, renameRepo, candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10, 90, 7,
            420);

    svc.runIfFree(); // scheduler/startup entry — submits runLocked to the service's executor

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              List<java.util.Map<String, Object>> rows =
                  jdbc.queryForList(
                      "SELECT status, rows_written, finished_at FROM ingest_runs "
                          + "WHERE source = 'BHAVCOPY' AND status IN ('SUCCESS', 'FAILURE') "
                          + "ORDER BY started_at DESC LIMIT 1");
              assertThat(rows).hasSize(1);
              assertThat(rows.get(0).get("status")).isEqualTo("SUCCESS");
              assertThat(rows.get(0).get("rows_written")).isNotNull();
              assertThat(rows.get(0).get("finished_at")).isNotNull();
            });
  }

  @Test
  @Order(5)
  void refetchFillsAPartiallyCapturedDayTheCatchupWouldSkip() {
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol LIKE 'RFT%'");
    jdbc.update("DELETE FROM candles WHERE tradingsymbol LIKE 'RFT%'");
    LocalDate day = LocalDate.of(2026, 6, 16); // a past trading day (Tue), < the fixed 06-18 clock

    // A PARTIAL capture: only RFTA is stored, so the day is 'present' and the self-healing catch-up
    // anti-joins it away — RFTB is never back-filled by an ordinary run (§8d, the gap this closes).
    nseRepo.upsertAll(List.of(nseRow(day, "RFTA", "EQ", "10", "11", "9", "10", 100L)));
    assertThat(rawNseCount("RFTB")).isZero();

    // The publisher now serves the FULL day (both symbols).
    BhavcopyFetcher full =
        new BhavcopyFetcher() {
          @Override
          public List<BhavcopyRow> fetchLatest() {
            return List.of();
          }

          @Override
          public List<BhavcopyRow> fetchForDate(LocalDate d) {
            return d.equals(day)
                ? List.of(
                    nseRow(day, "RFTA", "EQ", "10", "11", "9", "10", 100L),
                    nseRow(day, "RFTB", "EQ", "20", "21", "19", "20", 200L))
                : List.of();
          }
        };
    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            full, nseRepo, emptyBse(), bseRepo, emptyCa(), emptyBseCa(), caRepo, dividendRepo, renameRepo,
            candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10, 90, 7, 420);

    BhavcopyBackfillService.RefetchResult result = svc.refetchDate(day);

    // The targeted re-fetch re-pulls the whole day, filling the missing symbol + projecting its candle.
    assertThat(result.nse().bhavRows()).isEqualTo(2);
    assertThat(rawNseCount("RFTB")).isEqualTo(1);
    assertThat(close("NSE", "RFTB", day)).isEqualByComparingTo("20");
    assertThat(close("NSE", "RFTA", day)).isEqualByComparingTo("10"); // existing row untouched
  }

  @Test
  @Order(6)
  void dividendsLandSeparatelyWithoutCrossingIntoRatiosOrCandles() {
    NseCorporateActionFetcher nseActions =
        (from, to) ->
            List.of(
                new NseCorporateActionFetcher.CaRecord(
                    "DIVITNSE", "INEDIVIT0001", D2, "FINAL DIVIDEND RS 2.50 PER SHARE"),
                new NseCorporateActionFetcher.CaRecord(
                    "DIVITPCT", "INEDIVIT0003", D2, "DIVIDEND 50%"),
                new NseCorporateActionFetcher.CaRecord(
                    "DIVITSPLIT",
                    "INEDIVIT0002",
                    D2,
                    "Face Value Split From Rs 10/- To Rs 2/-"));
    BseCorporateActionFetcher bseActions =
        (from, to) ->
            List.of(
                new BseCorporateActionFetcher.BseCaRecord(
                    "990001", "DIVITBSE", D2, "INTERIM DIVIDEND - RE 1 PER SHARE"),
                new BseCorporateActionFetcher.BseCaRecord(
                    "990002", "DIVITBONUS", D2, "Bonus issue 1:1"));
    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            emptyNse(), nseRepo, emptyBse(), bseRepo, nseActions, bseActions, caRepo,
            dividendRepo, renameRepo, candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10, 90, 7,
            420);

    assertThat(svc.runRatios()).isEqualTo(2); // only the split + bonus ratio writes are counted
    assertThat(svc.runRatios()).isEqualTo(2); // replay is idempotent for both side tables

    assertThat(
            jdbc.queryForMap(
                "SELECT amount, subject, isin, source FROM dividends"
                    + " WHERE exchange = 'NSE' AND tradingsymbol = 'DIVITNSE' AND ex_date = ?",
                D2))
        .containsEntry("amount", new BigDecimal("2.5000"))
        .containsEntry("subject", "FINAL DIVIDEND RS 2.50 PER SHARE")
        .containsEntry("isin", "INEDIVIT0001")
        .containsEntry("source", "NSE");
    assertThat(
            jdbc.queryForMap(
                "SELECT amount, subject, isin, source FROM dividends"
                    + " WHERE exchange = 'BSE' AND tradingsymbol = 'DIVITBSE' AND ex_date = ?",
                D2))
        .containsEntry("amount", new BigDecimal("1.0000"))
        .containsEntry("subject", "INTERIM DIVIDEND - RE 1 PER SHARE")
        .containsEntry("isin", null)
        .containsEntry("source", "BSE");
    assertThat(
            jdbc.queryForMap(
                "SELECT amount, subject FROM dividends"
                    + " WHERE exchange = 'NSE' AND tradingsymbol = 'DIVITPCT' AND ex_date = ?",
                D2))
        .containsEntry("amount", null)
        .containsEntry("subject", "DIVIDEND 50%");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dividends WHERE tradingsymbol LIKE 'DIVIT%'", Long.class))
        .isEqualTo(3L);

    assertThat(caRepo.ratiosFor("NSE", "DIVITNSE")).isEmpty();
    assertThat(caRepo.ratiosFor("BSE", "DIVITBSE")).isEmpty();
    assertThat(caRepo.ratiosFor("NSE", "DIVITSPLIT"))
        .singleElement()
        .satisfies(r -> assertThat(r.ratio()).isEqualByComparingTo("0.2"));
    assertThat(caRepo.ratiosFor("BSE", "DIVITBONUS"))
        .singleElement()
        .satisfies(r -> assertThat(r.ratio()).isEqualByComparingTo("0.5"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dividends WHERE tradingsymbol IN ('DIVITSPLIT','DIVITBONUS')",
                Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM candles WHERE tradingsymbol LIKE 'DIVIT%'", Long.class))
        .isZero();
  }

  /**
   * The catch-up driven by the REAL {@link LiveBhavcopyFetcher} against a stubbed archive that
   * answers 200 with the PREVIOUS trading day's file — exactly what NSE does under most holiday
   * URLs. Nothing may be written: storing H-1's rows leaves the requested day H missing forever, so
   * the anti-join re-probes it (and re-stamps H-1's {@code fetched_at}) on every subsequent run.
   */
  @Test
  @Order(7)
  void misdatedArchivePayloadIsRefusedAndWritesNothing() {
    wireMock.resetAll();
    jdbc.update("DELETE FROM nse_eod_bhavcopy"); // deterministic watermark (same reset as @Order(2))
    jdbc.update("DELETE FROM candles WHERE tradingsymbol IN ('MISDT', 'WELLDT')");
    LocalDate requested = LocalDate.of(2026, 6, 18); // 'today' under the fixed clock
    LocalDate served = LocalDate.of(2026, 6, 17); // what the archive actually hands back

    stubArchive(requested, archiveCsv("MISDT", served));

    BhavcopyBackfillService.ExchangeResult result = liveNseBackfill().runNse();

    assertThat(result.days()).isZero();
    assertThat(result.bhavRows()).isZero();
    assertThat(rawNseCount("MISDT")).isZero();
    assertThat(close("NSE", "MISDT", served)).isNull();
    assertThat(nseRepo.maxTradeDate()).isNull(); // the shadow day was never written under H-1
  }

  /** The guard must refuse mis-dated payloads WITHOUT refusing correctly-dated ones. */
  @Test
  @Order(8)
  void correctlyDatedArchivePayloadStillLands() {
    wireMock.resetAll();
    jdbc.update("DELETE FROM nse_eod_bhavcopy");
    jdbc.update("DELETE FROM candles WHERE tradingsymbol IN ('MISDT', 'WELLDT')");
    LocalDate requested = LocalDate.of(2026, 6, 18);

    stubArchive(requested, archiveCsv("WELLDT", requested));

    BhavcopyBackfillService.ExchangeResult result = liveNseBackfill().runNse();

    assertThat(result.days()).isEqualTo(1);
    assertThat(result.bhavRows()).isEqualTo(1);
    assertThat(rawNseCount("WELLDT")).isEqualTo(1);
    assertThat(nseRepo.maxTradeDate()).isEqualTo(requested);
    assertThat(close("NSE", "WELLDT", requested)).isEqualByComparingTo("104");
  }

  /** The backfill wired to the REAL NSE fetcher pointed at WireMock (BSE/CA sides stay empty). */
  private BhavcopyBackfillService liveNseBackfill() {
    return new BhavcopyBackfillService(
        new LiveBhavcopyFetcher(
            new NseHttpClient(RestClient.builder(), wireMock.baseUrl()),
            wireMock.baseUrl(),
            CLOCK,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            in.arthayantra.marketcalendar.MarketCalendar.nse()),
        nseRepo, emptyBse(), bseRepo, emptyCa(), emptyBseCa(), caRepo, dividendRepo, renameRepo, candles,
        CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10, 90, 7, 420);
  }

  /** Only this date is published; every other archive URL 404s, as on a real non-trading day. */
  private static void stubArchive(LocalDate urlDate, String csv) {
    wireMock.stubFor(
        get(urlPathEqualTo(
                "/products/content/sec_bhavdata_full_"
                    + urlDate.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
                    + ".csv"))
            .willReturn(aResponse().withStatus(200).withBody(csv)));
  }

  /** One EQ row in the real sec_bhavdata_full shape (leading-space cells), close = 104. */
  private static String archiveCsv(String symbol, LocalDate rowDate) {
    return "SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE,"
        + " CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY,"
        + " DELIV_PER\n"
        + symbol
        + ", EQ, "
        + rowDate.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH))
        + ", 100.00, 101.00, 105.00, 99.00, 103.00, 104.00, 102.00, 1000, 10.00, 10, 500, 50.00\n";
  }

  /**
   * N2 / #1285: a "Change in Name" corporate action used to fall through the non-price-adjustment
   * {@code continue} and vanish — not logged, not counted, not stored — even though it arrives WITH
   * its ISIN attached and is the best rename signal available. It must now land in {@code
   * symbol_rename_events} with the from/to names split out, while still writing NO ratio, NO
   * dividend and NO candle. The bare-subject variant proves the row is kept even when the feed does
   * not spell the names (the ex-date + ISIN are the useful part), and the "Change of Name … Face
   * Value Split" variant pins the KNOWN precedence hole: the split parser claims it first, so no
   * rename event is recorded for it.
   */
  /**
   * N2 follow-up, 2026-09-03: the feed's partition must be COUNTED, not just its successful arm.
   *
   * <p>⚠ The defect this guards is one the platform already lived through invisibly. The rename
   * capture shipped 2026-08-10 and sat at ZERO rows for 24 days while ~5,063 non-ratio actions
   * flowed through the same loop every run — and nothing could see it, because a subject matching
   * NEITHER parser fell through the {@code continue} leaving no log line and no count. "The feed
   * carries no name changes" and "the parser matches no name changes" were indistinguishable from
   * outside: both look like silence.
   *
   * <p>The fixture deliberately contains one of each arm plus an unmatched buyback, because a
   * partition that only ever sees matched subjects cannot demonstrate the arm that was missing.
   */
  @Test
  @Order(10)
  void theCorporateActionFeedPartitionIsCounted() {
    NseCorporateActionFetcher nseActions =
        (from, to) ->
            List.of(
                new NseCorporateActionFetcher.CaRecord(
                    "PARTBONUS", "INEPART0001", D2, "Bonus 1:1"),
                new NseCorporateActionFetcher.CaRecord(
                    "PARTDIV", "INEPART0002", D2, "Interim Dividend Rs 5 Per Share"),
                new NseCorporateActionFetcher.CaRecord(
                    "PARTNAME",
                    "INEPART0003",
                    D2,
                    "Change In Name From Alpha Limited To Beta Limited"),
                new NseCorporateActionFetcher.CaRecord(
                    "PARTBUYBACK", "INEPART0004", D2, "Buyback Of Equity Shares"));
    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            emptyNse(), nseRepo, emptyBse(), bseRepo, nseActions, emptyBseCa(), caRepo,
            dividendRepo, renameRepo, candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10,
            90, 7, 420);

    BhavcopyBackfillService.CaPartition p = svc.runNseRatios();

    assertThat(p.seen()).as("every record the feed returned").isEqualTo(4);
    assertThat(p.ratios()).isEqualTo(1);
    assertThat(p.dividends()).isEqualTo(1);
    assertThat(p.nameChanges())
        .as("the arm that has been at ZERO in production since 2026-08-10")
        .isEqualTo(1);
    assertThat(p.unmatched())
        .as("the buyback — the arm that previously left NO trace of any kind")
        .isEqualTo(1);
    assertThat(p.unmatchedSamples())
        .as("a sample must be carried, or the count cannot be diagnosed")
        .containsExactly("Buyback Of Equity Shares");
  }

  @Test
  @Order(9)
  void changeInNameIsCapturedInsteadOfDiscarded() {
    jdbc.update("DELETE FROM symbol_rename_events WHERE symbol LIKE 'RENAM%'");
    NseCorporateActionFetcher nseActions =
        (from, to) ->
            List.of(
                new NseCorporateActionFetcher.CaRecord(
                    "RENAMFULL",
                    "INERENAM0001",
                    D2,
                    "Change In Name From Gujarat Gas Limited To Gujarat Energy Limited"),
                new NseCorporateActionFetcher.CaRecord(
                    "RENAMBARE", "INERENAM0002", D2, "Change in Name"),
                new NseCorporateActionFetcher.CaRecord(
                    "RENAMSPLIT",
                    "INERENAM0003",
                    D2,
                    "Change of Name & Face Value Split From Rs 10/- To Rs 2/-"));
    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            emptyNse(), nseRepo, emptyBse(), bseRepo, nseActions, emptyBseCa(), caRepo,
            dividendRepo, renameRepo, candles, CLOCK, event -> {}, noopNtfy(), ledger, "EQ,BE", 10,
            90, 7, 420);

    assertThat(svc.runRatios()).isEqualTo(1); // only RENAMSPLIT's face-value split writes a ratio
    assertThat(svc.runRatios()).isEqualTo(1); // replay is idempotent for the rename side table too

    assertThat(
            jdbc.queryForMap(
                "SELECT isin, from_name, to_name, subject, source FROM symbol_rename_events"
                    + " WHERE exchange = 'NSE' AND symbol = 'RENAMFULL' AND ex_date = ?",
                D2))
        .containsEntry("isin", "INERENAM0001")
        .containsEntry("from_name", "Gujarat Gas Limited")
        .containsEntry("to_name", "Gujarat Energy Limited")
        .containsEntry("source", "NSE");
    assertThat(
            jdbc.queryForMap(
                "SELECT isin, from_name, to_name FROM symbol_rename_events"
                    + " WHERE exchange = 'NSE' AND symbol = 'RENAMBARE' AND ex_date = ?",
                D2))
        .containsEntry("isin", "INERENAM0002")
        .containsEntry("from_name", null)
        .containsEntry("to_name", null);
    // Precedence hole, asserted so it stays visible: the split parser wins, so no rename row.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM symbol_rename_events WHERE symbol = 'RENAMSPLIT'",
                Long.class))
        .isZero();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM symbol_rename_events WHERE symbol LIKE 'RENAM%'", Long.class))
        .isEqualTo(2L);
    assertThat(caRepo.ratiosFor("NSE", "RENAMFULL")).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dividends WHERE tradingsymbol LIKE 'RENAM%'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM candles WHERE tradingsymbol LIKE 'RENAM%'", Long.class))
        .isZero();
  }

  private static BhavcopyFetcher emptyNse() {
    return new BhavcopyFetcher() {
      @Override
      public List<BhavcopyRow> fetchLatest() {
        return List.of();
      }

      @Override
      public List<BhavcopyRow> fetchForDate(LocalDate date) {
        return List.of();
      }
    };
  }

  /** Blank-topic NtfyClient — a silent no-op (the client short-circuits before any HTTP). */
  private static in.arthayantra.marketdata.alerts.NtfyClient noopNtfy() {
    return new in.arthayantra.marketdata.alerts.NtfyClient(
        org.springframework.web.client.RestClient.builder(),
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        "https://ntfy.sh",
        "");
  }

  private static BseBhavcopyFetcher emptyBse() {
    return date -> List.of();
  }

  private static NseCorporateActionFetcher emptyCa() {
    return (from, to) -> List.of();
  }

  private void cleanupOwnRows() {
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol LIKE 'UNIV%'");
    jdbc.update("DELETE FROM bse_eod_bhavcopy WHERE ticker LIKE 'UNIV%'");
    jdbc.update("DELETE FROM eod_corporate_actions WHERE tradingsymbol LIKE 'UNIV%'");
    jdbc.update("DELETE FROM candles WHERE tradingsymbol LIKE 'UNIV%'");
  }

  // ---- assertions helpers ------------------------------------------------------------------

  private BigDecimal close(String exchange, String symbol, LocalDate d) {
    List<Candle> rows = candles.range(exchange, symbol, "1d", bucket(d), bucket(d).plusDays(1));
    return rows.isEmpty() ? null : rows.get(0).close();
  }

  private String source(String exchange, String symbol, LocalDate d) {
    return candles.range(exchange, symbol, "1d", bucket(d), bucket(d).plusDays(1)).get(0).source();
  }

  private long rawNseCount(String symbol) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM nse_eod_bhavcopy WHERE symbol = ?", Long.class, symbol);
    return n == null ? 0 : n;
  }

  private static OffsetDateTime bucket(LocalDate d) {
    return OffsetDateTime.of(d, LocalTime.MIDNIGHT, IST);
  }

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // ---- stub fetchers (canned rows for the fixed dates) -------------------------------------

  private BhavcopyFetcher nseStub() {
    return new BhavcopyFetcher() {
      @Override
      public List<BhavcopyRow> fetchLatest() {
        return List.of();
      }

      @Override
      public List<BhavcopyRow> fetchForDate(LocalDate date) {
        if (date.equals(D1)) {
          return List.of(
              nseRow(D1, "UNIVA", "EQ", "100", "105", "99", "104", 1000L),
              nseRow(D1, "UNIVADJ", "EQ", "200", "200", "200", "200", 1000L));
        }
        if (date.equals(D2)) {
          return List.of(
              nseRow(D2, "UNIVA", "EQ", "110", "115", "109", "114", 1100L),
              nseRow(D2, "UNIVB", "BE", "50", "52", "49", "51", 500L),
              nseRow(D2, "UNIVG", "GS", "1000", "1001", "999", "1000", 10L),
              nseRow(D2, "UNIVADJ", "EQ", "100", "100", "100", "100", 1000L));
        }
        return List.of();
      }
    };
  }

  private static BhavcopyFetcher.BhavcopyRow nseRow(
      LocalDate d, String sym, String series, String o, String h, String l, String c, long vol) {
    return new BhavcopyFetcher.BhavcopyRow(
        d, sym, series, null, bd(o), bd(h), bd(l), null, bd(c), null, vol, null, null, null, null);
  }

  private BseBhavcopyFetcher bseStub() {
    return date -> {
      if (date.equals(D2)) {
        return List.of(
            new BseBhavcopyFetcher.BseBhavRow(
                D2, "900001", "UNIVBSE", "INETEST00001", "A",
                bd("199"), bd("200"), bd("205"), bd("199"), bd("204"), bd("204"), 2000L, bd("408000"), 88L),
            new BseBhavcopyFetcher.BseBhavRow(
                D2, "900002", "UNIVBSEONLY", "INEONLY00001", "B",
                bd("99"), bd("100"), bd("102"), bd("98"), bd("101"), bd("101"), 500L, bd("50500"), 22L));
      }
      return List.of();
    };
  }

  /** BSE corporate-actions feed: a bonus on the BSE-only scrip (no NSE twin), keyed by scrip code. */
  private BseCorporateActionFetcher bseCaStub() {
    return (from, to) ->
        List.of(new BseCorporateActionFetcher.BseCaRecord("900002", "UNIVBSEONLY", D2, "Bonus issue 1:1"));
  }

  private static BseCorporateActionFetcher emptyBseCa() {
    return (from, to) -> List.of();
  }

  private NseCorporateActionFetcher caStub() {
    return (from, to) ->
        List.of(
            new NseCorporateActionFetcher.CaRecord("UNIVADJ", "INETEST00001", D2, "Bonus 1:1"));
  }
}
