package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.bse.BseBhavcopyFetcher;
import in.arthayantra.marketdata.bse.BseCorporateActionFetcher;
import in.arthayantra.marketdata.bse.BseEodBhavcopyRepository;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.candles.CandleRepository;
import in.arthayantra.marketdata.candles.EodCorporateActionRepository;
import in.arthayantra.marketdata.candles.EquitySplitBonusAdjuster;
import in.arthayantra.marketdata.nse.BhavcopyFetcher;
import in.arthayantra.marketdata.nse.NseCorporateActionFetcher;
import in.arthayantra.marketdata.nse.NseEodBhavcopyRepository;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end EOD bhavcopy backfill against the real Timescale container: watermark catch-up,
 * EQ/BE projection into candles, DO-NOTHING vs a Kite-owned bar, GS-series exclusion, BSE projection,
 * NSE→BSE ratio cross-map by ISIN, read-time split/bonus adjustment, and the CorporateActionJob gate.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
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
  @Autowired EquitySplitBonusAdjuster adjuster;
  @Autowired JdbcTemplate jdbc;

  @Test
  @Order(1)
  void fullBackfillProjectionRatiosAndAdjustment() {
    cleanupOwnRows();

    BhavcopyBackfillService svc =
        new BhavcopyBackfillService(
            nseStub(), nseRepo, bseStub(), bseRepo, caStub(), bseCaStub(), caRepo, candles, CLOCK,
            event -> {}, "EQ,BE", 10, 90, 7, 420);

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
    assertThat(nseRepo.maxTradeDate()).isEqualTo(D2); // watermark advanced
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
            flaky, nseRepo, emptyBse(), bseRepo, emptyCa(), emptyBseCa(), caRepo, candles, CLOCK,
            event -> {}, "EQ,BE", 10, 90, 7, 420);

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
