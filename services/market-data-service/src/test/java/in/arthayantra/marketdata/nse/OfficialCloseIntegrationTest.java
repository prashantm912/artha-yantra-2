package in.arthayantra.marketdata.nse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/market/eod-close} + {@link NseEodBhavcopyRepository#officialClosesOn} — the
 * OFFICIAL-NSE-close read the swing settle re-prices its exit fills against (ledger H9).
 *
 * <p><b>Fixture discipline.</b> Every symbol is unique to its own test METHOD (the ITs share a
 * singleton DB with no per-method cleanup), and every seed lands in {@link #YEAR} — a FAR PAST year.
 * The direction is load-bearing and was learned the hard way (H19, recorded at
 * {@code EquityControllerIntegrationTest:41-53}): a FUTURE {@code trade_date} becomes the global
 * {@code max(trade_date)} and detonates every as-of-latest query in the service, while a far-past
 * date can never win a {@code max()} and is inert for all of them. This class's own reads are pinned
 * to an explicit date, so history that old serves it perfectly.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class OfficialCloseIntegrationTest extends MarketDataIntegrationTestBase {

  /** Far PAST — never the global max(trade_date). See the class javadoc. */
  private static final int YEAR = 1997;

  private static final LocalDate SESSION = LocalDate.of(YEAR, 3, 12);
  private static final LocalDate OTHER_SESSION = LocalDate.of(YEAR, 3, 11);

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired NseEodBhavcopyRepository repository;

  @AfterEach
  void purgeOwnFixtures() {
    jdbc.update("DELETE FROM nse_eod_bhavcopy WHERE symbol LIKE 'H9OC%'");
  }

  @Test
  void eqOutranksBeEvenThoughBeSortsFirstAlphabetically() {
    // ⚠️ THE ONE CASE THAT DISCRIMINATES THE RANK FUNCTION FROM THE QUERY'S `ORDER BY symbol, series`.
    // Every collision that actually occurs in the live table (EQ+P1, EQ+T0, EQ+N3, BE+P1) happens to
    // put the correct answer first alphabetically too, so a rank function that had been deleted
    // entirely would still pass all of them. "BE" sorts BEFORE "EQ", so only this shape can tell the
    // declared precedence apart from an accident of the alphabet. It does not occur in production —
    // measured 2026-08-25, EQ and BE never collide on one date (0 pairs) — which is precisely why it
    // has to be constructed here rather than waited for.
    seed("H9OCEQBE", SESSION, "BE", "88.0000", "88.0000");
    seed("H9OCEQBE", SESSION, "EQ", "91.5000", "91.0000");

    List<OfficialClose> rows = repository.officialClosesOn(SESSION, List.of("H9OCEQBE"));

    assertThat(rows)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.series()).isEqualTo("EQ");
              assertThat(r.closePrice()).isEqualByComparingTo(new BigDecimal("91.5000"));
            });
  }

  @Test
  void eqWinsOverANonCashSeriesOnTheSameDate() {
    // The collision that actually occurs in production. Measured 2026-08-25 over the trailing ~400
    // days of the live table: 497 (trade_date, symbol) pairs carry an EQ row AND a row in another
    // series — EQ+P1, EQ+T0, EQ+N3, BE+P1 — and 327 of those 497 DISAGREE on close_price. So the
    // precedence decides a PRICE, not a formatting detail, and "no rows collide" would be false.
    // (This shape is not alphabet-discriminating; the EQ-vs-BE test above is the one that is.)
    seed("H9OCEQN3", SESSION, "EQ", "100.5000", "100.2500");
    seed("H9OCEQN3", SESSION, "N3", "77.7700", "77.7700");

    List<OfficialClose> rows = repository.officialClosesOn(SESSION, List.of("H9OCEQN3"));

    assertThat(rows)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.series()).as("EQ outranks a special-settlement series").isEqualTo("EQ");
              assertThat(r.closePrice()).isEqualByComparingTo(new BigDecimal("100.5000"));
            });
  }

  @Test
  void beWinsOverANonCashSeriesOnTheSameDate() {
    // The BE+P1 shape — the other real collision. BE must outrank a special series for exactly the
    // same reason EQ does: the cash-universe row is the one the book trades.
    seed("H9OCBEP1", SESSION, "P1", "55.0000", "55.0000");
    seed("H9OCBEP1", SESSION, "BE", "61.2500", "61.0000");

    List<OfficialClose> rows = repository.officialClosesOn(SESSION, List.of("H9OCBEP1"));

    assertThat(rows)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.series()).isEqualTo("BE");
              assertThat(r.closePrice()).isEqualByComparingTo(new BigDecimal("61.2500"));
            });
  }

  @Test
  void aBeOnlySymbolIsReturnedRatherThanSilentlyDropped() {
    // THE EQ-ONLY TRAP, and it is not hypothetical: measured 2026-08-25 the live swing books hold
    // TIRUPATIFL and UNIDT as BE-ONLY names. A `series = 'EQ'` filter here would omit exactly those
    // two every night — and it would fail in the ALARMING direction, reading as an outage rather
    // than as a filter artifact (H24). If this test ever goes green against an EQ-only query, the
    // query is wrong and this test is the only thing that can say so.
    seed("H9OCBEONLY", SESSION, "BE", "412.8000", "412.0000");

    List<OfficialClose> rows = repository.officialClosesOn(SESSION, List.of("H9OCBEONLY"));

    assertThat(rows)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.tradingsymbol()).isEqualTo("H9OCBEONLY");
              assertThat(r.series()).isEqualTo("BE");
              assertThat(r.closePrice()).isEqualByComparingTo(new BigDecimal("412.8000"));
            });
  }

  @Test
  void aSymbolWhoseOnlyRowIsANonCashSeriesIsOmittedSoTheExitTakesTheCandleFallback() {
    // ⚠️ CRITICAL. The first cut of this query was series-AGNOSTIC and ranked non-cash rows LAST,
    // which ACCEPTS them when nothing better exists rather than rejecting them — so a symbol with no
    // EQ/BE row that date would have priced a live swing exit off a DIFFERENT INSTRUMENT.
    //
    // The population is large and the instruments are not close cousins: measured 2026-08-25,
    // 170,950 (trade_date, symbol) pairs carry a non-cash series and NO EQ/BE row — 99,565 SM (SME
    // platform), 27,879 ST, and 14,142 GS, which are GOVERNMENT SECURITIES. Zero are currently-held
    // swing symbols, so this was latent rather than live; that is a statement about today's book,
    // not about the guard.
    //
    // OMITTED, not fallback-priced-here: absence is what routes the settle to its own counted,
    // alerted candle-close fallback. Answering with an SM row would be silent and confident.
    seed("H9OCSMONLY", SESSION, "SM", "77.0000", "77.0000");
    seed("H9OCSMONLY", SESSION, "GS", "9999.0000", "9999.0000");

    assertThat(repository.officialClosesOn(SESSION, List.of("H9OCSMONLY"))).isEmpty();
  }

  @Test
  void aRowWithANullClosePriceIsOmittedRatherThanReturnedAsNull() {
    // The caller must be able to tell "the exchange has not published this" from "published as
    // nothing" — an omitted symbol is what routes the swing settle to its counted, alerted fallback.
    seed("H9OCNULL", SESSION, "EQ", null, "31.0000");

    assertThat(repository.officialClosesOn(SESSION, List.of("H9OCNULL"))).isEmpty();
  }

  @Test
  void aRowOnADifferentDateIsNotReturnedForTheRequestedSession() {
    // WEAKENING proof for the date filter: the symbol EXISTS and has a perfectly good close — just
    // for the previous session. A read that resolved the wrong date would return 129.0000 here and
    // look entirely healthy, which is the precise failure H9 exists to prevent (a price is only
    // meaningful with its session attached).
    seed("H9OCDATE", OTHER_SESSION, "EQ", "129.0000", "129.0000");

    assertThat(repository.officialClosesOn(SESSION, List.of("H9OCDATE"))).isEmpty();
    assertThat(repository.officialClosesOn(OTHER_SESSION, List.of("H9OCDATE"))).hasSize(1);
  }

  @Test
  void anEmptyOrBlankSymbolListReturnsEmptyWithoutQuerying() {
    // `IN ()` is a SQL syntax error, and a run with no open lots is an ordinary night rather than an
    // edge case — the settle must not throw on a money path because the book happens to be flat.
    assertThat(repository.officialClosesOn(SESSION, List.of())).isEmpty();
    assertThat(repository.officialClosesOn(SESSION, List.of("", "  "))).isEmpty();
  }

  @Test
  void theEndpointServesTheRowAsAJsonStringDecimalAndOmitsUnpublishedSymbols() throws Exception {
    // Decimals ride the wire as JSON STRINGS (ArthaJacksonAutoConfiguration registers
    // ToStringSerializer for BigDecimal platform-wide) while springdoc would infer `number` — the
    // @Schema(type = "string") on OfficialClose is what makes the captured spec tell the truth.
    // Asserting the RUNTIME type here is the half an annotation cannot prove.
    seed("H9OCWIRE", SESSION, "EQ", "1234.5600", "1234.0000");

    mockMvc
        .perform(
            get("/api/v1/market/eod-close")
                .param("exchange", "NSE")
                .param("date", SESSION.toString())
                .param("symbols", "H9OCWIRE,H9OCABSENT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].tradingsymbol").value("H9OCWIRE"))
        .andExpect(jsonPath("$.items[0].tradeDate").value(SESSION.toString()))
        .andExpect(jsonPath("$.items[0].series").value("EQ"))
        .andExpect(jsonPath("$.items[0].closePrice").value(instanceOf(String.class)))
        .andExpect(jsonPath("$.items[0].closePrice").value("1234.5600"));
  }

  @Test
  void aNonNseExchangeAnswersAnEmptyListRatherThanAnError() throws Exception {
    // Only NSE has a bhavcopy plane. The sole caller is an exit settle that may never be refused, so
    // an unknown exchange degrades to the fallback instead of throwing onto a money path.
    seed("H9OCEXCH", SESSION, "EQ", "10.0000", "10.0000");

    mockMvc
        .perform(
            get("/api/v1/market/eod-close")
                .param("exchange", "BSE")
                .param("date", SESSION.toString())
                .param("symbols", "H9OCEXCH"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  private void seed(String symbol, LocalDate date, String series, String close, String last) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy (trade_date, symbol, series, close_price, last_price,"
            + " fetched_at) VALUES (?, ?, ?, ?, ?, now())"
            + " ON CONFLICT (trade_date, symbol, series) DO UPDATE SET close_price ="
            + " EXCLUDED.close_price, last_price = EXCLUDED.last_price",
        Date.valueOf(date),
        symbol,
        series,
        close == null ? null : new BigDecimal(close),
        last == null ? null : new BigDecimal(last));
  }
}
