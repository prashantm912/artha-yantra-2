package in.arthayantra.marketdata.context;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * INT increment I2 (intelligence-layer design §6.1.2–4 / §6.4): the market-data futures / equity /
 * FII digests, the cross-expiry compare, and the three NEW bhavcopy folds (above-MA counts, universe
 * delivery-z, date-parameterized sector rotation), exercised against the Timescale container + the
 * real deploy/flyway marketdata lineage. Isolation on the shared singleton DB uses unique synthetic
 * identifiers where possible plus symbol-and-date-scoped cleanup for bhavcopy rows that require
 * real sector-mapped tickers. The futures universe is overridden to a unique underlying so the
 * digest only sees the seeded snapshots.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.futures.oi-snapshot-underlyings=FUTCTX",
      "artha.futures.bank-stocks=FUTCTX",
      "artha.context.futures-term-index=FUTCTX"
    })
@AutoConfigureMockMvc
class MarketContextI2IntegrationTest extends MarketDataIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final LocalDate OWN_BHAVCOPY_FROM = LocalDate.of(2026, 11, 2);
  private static final LocalDate OWN_BHAVCOPY_TO = LocalDate.of(2026, 12, 15);
  private static final List<String> OWN_BHAVCOPY_SYMBOLS =
      List.of("CTXRISER", "CTXFALLER", "TCS", "INFY", "HDFCBANK");

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;

  /**
   * OWN the FII-derivative trust precondition instead of assuming it.
   *
   * <p>{@code fiiDigestGatesDerivativeOnAnalyticsAndFoldsCashAndParticipant} asserts the
   * analytics-disabled reason, which {@code FiiDigestService:265-270} only emits when the
   * {@code NSE_FII_DERIVATIVE} ingest trust is NOT "OK". That trust is the {@link
   * in.arthayantra.marketdata.canary.IngestHealthBoard} verdict for {@code
   * previousTradingDay(today)} — read off the REAL clock ({@code ClockConfig} = {@code
   * Clock.systemUTC()}).
   *
   * <p>Meanwhile {@code NseEodScheduler:54} ({@code @EventListener(ApplicationReadyEvent)}) fires on
   * EVERY Spring context boot in this suite and writes a real SUCCESS {@code NSE_FII_DERIVATIVE}
   * ledger row stamped {@code now()} (the mock fetcher is {@code @Profile("!live")}, so it is always
   * present here). While that row is still "today" it sits OUTSIDE the board's window and the test
   * passes — but the instant IST midnight rolls, it becomes the board's newest settled day, flips
   * the verdict GREEN, and the reason silently changes to "no FII derivative row for the session".
   *
   * <p>That made the test fail for roughly one minute per day (reproduced 2026-07-17 by seeding this
   * exact row) — an empty-diff main-red generator, and CI's 18:30 UTC window IS IST midnight.
   * Purging the source's ledger rows makes the precondition deterministic regardless of wall-clock.
   */
  @BeforeEach
  void ownFiiDerivativeTrustPrecondition() {
    jdbc.update("DELETE FROM ingest_runs WHERE source = 'NSE_FII_DERIVATIVE'");
  }

  /** Remove only this class's symbols within its seeded date envelope from the shared singleton DB. */
  @AfterEach
  void dropSeededBhavcopy() {
    for (String symbol : OWN_BHAVCOPY_SYMBOLS) {
      jdbc.update(
          "DELETE FROM nse_eod_bhavcopy WHERE symbol = ? AND trade_date BETWEEN ? AND ?",
          symbol,
          java.sql.Date.valueOf(OWN_BHAVCOPY_FROM),
          java.sql.Date.valueOf(OWN_BHAVCOPY_TO));
    }
  }

  // ---- futures-digest --------------------------------------------------------------------------

  @Test
  void futuresDigestFoldsSeededSnapshotIntoAQuadrant() throws Exception {
    LocalDate exp = LocalDate.of(2026, 7, 30);
    // Two 15m buckets: OI +200 on a +10 price move ⇒ LONG_BUILDUP.
    OffsetDateTime prior = OffsetDateTime.of(2026, 6, 24, 10, 2, 0, 0, IST);
    OffsetDateTime now = OffsetDateTime.of(2026, 6, 24, 10, 17, 0, 0, IST);
    seedFut(prior, "FUTCTX", "FUTCTX30JUL", exp, "100", 1000L);
    seedFut(now, "FUTCTX", "FUTCTX30JUL", exp, "110", 1200L, "100");

    mockMvc
        .perform(get("/api/v1/market/context/futures-digest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlyings[0].underlying").value("FUTCTX"))
        .andExpect(jsonPath("$.underlyings[0].oiChange").value(200))
        .andExpect(jsonPath("$.underlyings[0].interpretation").value("LONG_BUILDUP"))
        .andExpect(jsonPath("$.underlyings[0].pricePct").value("10.00"))
        .andExpect(jsonPath("$.banks.total").value(1))
        .andExpect(jsonPath("$.banks.longBuildup").value(1))
        // No capture today (seeded in the past) + no term structure in mock ⇒ honest DEGRADED.
        .andExpect(jsonPath("$.dataTrust").value("DEGRADED"));
  }

  // ---- equity-digest: the three NEW folds ------------------------------------------------------

  @Test
  void equityDigestComputesAboveMaAndDeliveryZAndAdvanceDecline() throws Exception {
    // Future session dates so the universe-wide folds isolate cleanly on the shared singleton DB
    // (no test seeds EQ bhavcopy beyond 2026-07-06, so this window holds only these rows).
    // 22 ascending sessions for a riser, 22 descending for a faller.
    LocalDate end = OWN_BHAVCOPY_TO;
    for (int i = 21; i >= 0; i--) {
      LocalDate d = end.minusDays(i);
      double riser = 100 + (21 - i); // ascending ⇒ latest close is the highest ⇒ above its MA20
      double faller = 200 - (21 - i); // descending ⇒ latest close is the lowest ⇒ below its MA20
      // Riser delivery baseline ~30% (with variance so stddev>0) then an 85% spike on the last
      // session ⇒ a large positive z-score. The faller's flat delivery (stddev 0) is excluded.
      double riserDeliv = (i == 0) ? 85.0 : (i % 2 == 0 ? 32.0 : 28.0);
      seedBhav(d, "CTXRISER", riser - 1, riser, riserDeliv);
      seedBhav(d, "CTXFALLER", faller + 1, faller, 40.0);
    }

    mockMvc
        .perform(get("/api/v1/market/context/equity-digest").param("date", end.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tradeDate").value(end.toString()))
        // A/D on the final session: riser up, faller down.
        .andExpect(jsonPath("$.advanceDecline.advances").value(1))
        .andExpect(jsonPath("$.advanceDecline.declines").value(1))
        // NEW fold: above-MA counts (both have a full 20-window; only the riser is above it).
        .andExpect(jsonPath("$.aboveMa.universe20").value(2))
        .andExpect(jsonPath("$.aboveMa.above20").value(1))
        .andExpect(jsonPath("$.aboveMa.universe50").value(0))
        // NEW fold: delivery-z outlier — the riser's 85% spike vs its ~30% baseline.
        .andExpect(jsonPath("$.deliveryOutliers[0].symbol").value("CTXRISER"))
        .andExpect(jsonPath("$.deliveryOutliers[0].z").isNotEmpty())
        .andExpect(jsonPath("$.returnsWindow").value("1w"))
        .andExpect(jsonPath("$.dataTrust").value("OK")); // clean window, not stale
  }

  @Test
  void equityDigestSectorRotationHonorsTheDateParameter() throws Exception {
    // NEW fold: the sector rotation is parameterized by `date`. Seed TWO sessions with DIFFERENT
    // sector performance, plus a LATER session; a digest for the middle date must reflect THAT
    // session (proving it is not hardwired to the latest session, the pre-I2 behavior). Future
    // dates isolate cleanly on the shared DB.
    LocalDate prior = OWN_BHAVCOPY_FROM;
    LocalDate target = LocalDate.of(2026, 11, 3);
    LocalDate later = LocalDate.of(2026, 11, 4);
    // target session: IT sector strong (+5%), Financials weak (-3%).
    seedBhav(prior, "TCS", 100, 100, 50); // flat prior
    seedBhav(prior, "INFY", 100, 100, 50);
    seedBhav(prior, "HDFCBANK", 100, 100, 50);
    seedBhav(target, "TCS", 100, 105, 50); // +5%
    seedBhav(target, "INFY", 100, 105, 50); // +5%
    seedBhav(target, "HDFCBANK", 100, 97, 50); // -3%
    // later session: the ranking is INVERTED — if the fold ignored `date` it would surface this.
    seedBhav(later, "TCS", 105, 100, 50); // IT down
    seedBhav(later, "INFY", 105, 100, 50);
    seedBhav(later, "HDFCBANK", 97, 103, 50); // Financials up

    mockMvc
        .perform(get("/api/v1/market/context/equity-digest").param("date", target.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tradeDate").value(target.toString()))
        // On the TARGET session IT leads (+5%) and Financials trails (-3%).
        .andExpect(jsonPath("$.topSectors[0].sector").value("Information Technology"))
        .andExpect(jsonPath("$.topSectors[0].avgChangePct").value("5.00"))
        .andExpect(jsonPath("$.bottomSectors[*].sector", hasItem("Financial Services")));
  }

  // ---- fii-digest: trust-gating demonstration --------------------------------------------------

  @Test
  void fiiDigestGatesDerivativeOnAnalyticsAndFoldsCashAndParticipant() throws Exception {
    // Past dates BEFORE any derivative row (the mock fetcher seeds one at "today"; the FiiDii IT
    // seeds one at 2026-06-16) so the derivative fold is legitimately empty and the analytics-off
    // gate fires; cash/participant on these dates are isolated (no test seeds FII ≤ 2026-05).
    // Cash: 3-session FII sell streak (a positive bound stops it); DII buys on the latest ⇒ divergence.
    LocalDate d0 = LocalDate.of(2026, 5, 4);
    LocalDate d1 = LocalDate.of(2026, 5, 1);
    LocalDate d2 = LocalDate.of(2026, 4, 30);
    LocalDate d3 = LocalDate.of(2026, 4, 29);
    seedFiiDii(d0, "FII/FPI", -800);
    seedFiiDii(d1, "FII/FPI", -400);
    seedFiiDii(d2, "FII/FPI", -200);
    seedFiiDii(d3, "FII/FPI", 10); // opposite sign ⇒ bounds the sell streak at 3
    seedFiiDii(d0, "DII", 500);
    // Participant OI: FII index futures long/short over 3 sessions (< 20 ⇒ low confidence).
    seedParticipant(d0, 1200, 800, 5000, 4000);
    seedParticipant(d1, 1000, 1000, 4800, 4200);
    seedParticipant(d2, 900, 1100, 4600, 4300);

    // Empty derivative table for d0 + a non-GREEN ingest source ⇒ "upstox analytics disabled"
    // (honest DEGRADED, not a fabricated number). The ingest sources are also gated into
    // trustReasons — the trust-gating demonstration.
    mockMvc
        .perform(get("/api/v1/market/context/fii-digest").param("date", d0.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cash.net").value("-800.00"))
        .andExpect(jsonPath("$.cash.streakDays").value(3))
        .andExpect(jsonPath("$.cash.streakSide").value("SELL"))
        .andExpect(jsonPath("$.dii.divergent").value(true))
        .andExpect(jsonPath("$.futuresLongShort.ratio").value("1.5000")) // 1200/800
        .andExpect(jsonPath("$.futuresLongShort.windowSessions").value(3))
        .andExpect(jsonPath("$.futuresLongShort.lowConfidence").value(true))
        .andExpect(jsonPath("$.participant.netContracts").value(1000)) // 5000-4000
        .andExpect(jsonPath("$.derivative.available").value(false))
        .andExpect(jsonPath("$.derivative.reason", containsString("upstox analytics disabled")))
        .andExpect(jsonPath("$.dataTrust").value("DEGRADED"))
        .andExpect(jsonPath("$.trustReasons", hasItem(containsString("NSE_FII_DII"))));

    // With a derivative row present, the same digest reports it available (the gate is not a constant).
    LocalDate d4 = LocalDate.of(2026, 5, 6);
    seedFiiDii(d4, "FII/FPI", -100);
    seedDerivative(d4, -1850);
    mockMvc
        .perform(get("/api/v1/market/context/fii-digest").param("date", d4.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.derivative.available").value(true))
        .andExpect(jsonPath("$.derivative.indexFuturesNet").value("-1850.00"));
  }

  // ---- expiry-compare --------------------------------------------------------------------------

  @Test
  void expiryCompareZipsTwoExpiriesByBucket() throws Exception {
    String u = "EXPCMP";
    LocalDate e1 = LocalDate.of(2026, 6, 25);
    LocalDate e2 = LocalDate.of(2026, 7, 30);
    OffsetDateTime t = OffsetDateTime.of(2026, 6, 24, 10, 1, 0, 0, IST);
    // Same bucket for both expiries so the zip aligns them.
    seedOption(t, u, e1, "22400", "CE", "120", 1000L);
    seedOption(t, u, e1, "22400", "PE", "40", 1500L);
    seedOption(t, u, e2, "22400", "CE", "200", 2000L);
    seedOption(t, u, e2, "22400", "PE", "90", 2500L);

    mockMvc
        .perform(
            get("/api/v1/market/context/expiry-compare")
                .param("name", u)
                .param("expiryA", e1.toString())
                .param("expiryB", e2.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.underlying").value(u))
        .andExpect(jsonPath("$.a.expiry").value(e1.toString()))
        .andExpect(jsonPath("$.b.expiry").value(e2.toString()))
        .andExpect(jsonPath("$.aligned[0].aTotalOi").value(2500)) // 1000 + 1500
        .andExpect(jsonPath("$.aligned[0].bTotalOi").value(4500)); // 2000 + 2500
  }

  @Test
  void expiryCompareIsBlockedNotErrorWhenNeitherExpiryHasData() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/market/context/expiry-compare")
                .param("name", "EXPNONE")
                .param("expiryA", "2026-06-25")
                .param("expiryB", "2026-07-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataTrust").value("BLOCKED"));
  }

  // ---- seed helpers ----------------------------------------------------------------------------

  private void seedFut(OffsetDateTime ts, String underlying, String tsym, LocalDate exp, String ltp, Long oi) {
    seedFut(ts, underlying, tsym, exp, ltp, oi, null);
  }

  private void seedFut(
      OffsetDateTime ts, String underlying, String tsym, LocalDate exp, String ltp, Long oi, String prevClose) {
    jdbc.update(
        "INSERT INTO futures_oi_snapshots"
            + " (ts, underlying, tradingsymbol, expiry, ltp, volume, oi, oi_change,"
            + "  day_open, day_high, day_low, prev_close)"
            + " VALUES (?,?,?,?,?::numeric,?,?,?,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        underlying,
        tsym,
        java.sql.Date.valueOf(exp),
        ltp,
        100L,
        oi,
        0L,
        null,
        null,
        null,
        prevClose);
  }

  private void seedBhav(LocalDate date, String symbol, double prevClose, double close, double delivPer) {
    jdbc.update(
        "INSERT INTO nse_eod_bhavcopy"
            + " (trade_date, symbol, series, prev_close, open_price, high_price, low_price, last_price,"
            + "  close_price, avg_price, ttl_trd_qnty, turnover_lacs, no_of_trades, deliv_qty, deliv_per, fetched_at)"
            + " VALUES (?,?,'EQ',?,?,?,?,?,?,?,1000,1,10,500,?,now()) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date),
        symbol,
        prevClose,
        close,
        close,
        close,
        close,
        close,
        close,
        delivPer);
  }

  private void seedFiiDii(LocalDate date, String category, double net) {
    jdbc.update(
        "INSERT INTO nse_eod_fii_dii (trade_date, category, buy_value, sell_value, net_value, fetched_at)"
            + " VALUES (?,?,0,0,?,now()) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date),
        category,
        net);
  }

  private void seedParticipant(LocalDate date, long idxLong, long idxShort, long totLong, long totShort) {
    jdbc.update(
        "INSERT INTO nse_eod_participant_oi"
            + " (trade_date, client_type, future_index_long, future_index_short,"
            + "  total_long_contracts, total_short_contracts, fetched_at)"
            + " VALUES (?, 'FII', ?, ?, ?, ?, now()) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date),
        idxLong,
        idxShort,
        totLong,
        totShort);
  }

  private void seedDerivative(LocalDate date, double net) {
    jdbc.update(
        "INSERT INTO fii_derivative_stats (trade_date, segment, buy_value, sell_value, net_value, fetched_at)"
            + " VALUES (?, 'INDEX_FUTURES', 0, 0, ?, now()) ON CONFLICT DO NOTHING",
        java.sql.Date.valueOf(date),
        net);
  }

  private void seedOption(
      OffsetDateTime ts, String u, LocalDate exp, String strike, String type, String ltp, Long oi) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots"
            + " (ts, underlying, expiry, strike, option_type, tradingsymbol, ltp, oi, oi_change, volume, spot_price)"
            + " VALUES (?,?,?,?::numeric,?,?,?::numeric,?,?,?,?::numeric) ON CONFLICT DO NOTHING",
        java.sql.Timestamp.from(ts.toInstant()),
        u,
        java.sql.Date.valueOf(exp),
        strike,
        type,
        u + strike + type,
        ltp,
        oi,
        0L,
        null,
        "22480.00");
  }
}
