package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.options.OiInterval;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the candle→chain OI derivation (historical OI without captured snapshots): bucketed {@code
 * oi}/{@code ltp} via {@code last()}, the {@code oi_change} bucket-over-bucket lag (null first
 * bucket), the {@code complete} coverage gate, front-expiry resolution, and — the load-bearing
 * parity — that the derived {@code oi} equals what {@link OptionsSnapshotReader#series} reads from
 * equivalent captured snapshots over the same window. Isolated on a far-future expiry (the IT DB is
 * shared with no per-test cleanup).
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class CandleDerivedChainReaderIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String EU = "NIFTY"; // expired_contracts.underlying_symbol
  private static final String DISPLAY = "NIFTY 50"; // options_chain_snapshots.underlying
  private static final LocalDate EXPIRY = LocalDate.of(2099, 1, 15);
  private static final LocalDate EXPIRY2 = LocalDate.of(2099, 2, 19);

  @Autowired private CandleDerivedChainReader reader;
  @Autowired private OptionsSnapshotReader snapshotReader;
  @Autowired private JdbcTemplate jdbc;

  private static OffsetDateTime ist(String hhmm) {
    return OffsetDateTime.parse("2099-01-13T" + hhmm + ":00+05:30");
  }

  private void candle(String sym, OffsetDateTime ts, String close, long oi, long vol) {
    java.math.BigDecimal px = new java.math.BigDecimal(close);
    jdbc.update(
        "INSERT INTO candles(exchange,tradingsymbol,interval,bucket,open,high,low,close,volume,oi,source)"
            + " VALUES('NFO',?,?,?,?,?,?,?,?,?,'BACKFILL') ON CONFLICT DO NOTHING",
        sym, "1m", Timestamp.from(ts.toInstant()), px, px, px, px, vol, oi);
  }

  private void contract(String sym, String type, double strike, LocalDate expiry, boolean complete) {
    jdbc.update(
        "INSERT INTO expired_contracts(exchange,tradingsymbol,instrument_type,underlying_symbol,expiry,"
            + "strike,lot_size,weekly,upstox_key,underlying_key,complete)"
            + " VALUES('NFO',?,?,?,?,?,75,true,?,?,?) ON CONFLICT (exchange,tradingsymbol) DO UPDATE SET"
            + " complete=EXCLUDED.complete",
        sym, type, EU, java.sql.Date.valueOf(expiry), strike, "uk-" + sym, "uk-NIFTY", complete);
  }

  private void snapshot(String sym, String type, double strike, OffsetDateTime ts, long oi) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots(ts,underlying,expiry,strike,option_type,tradingsymbol,oi,source)"
            + " VALUES(?,?,?,?,?,?,?,'LIVE') ON CONFLICT DO NOTHING",
        Timestamp.from(ts.toInstant()), DISPLAY, java.sql.Date.valueOf(EXPIRY), strike, type, sym, oi);
  }

  @BeforeEach
  void seed() {
    // one 23900 strike, CE + PE, over two 5-min buckets (09:15-09:20, 09:20-09:25) on 2099-01-13.
    contract("DERIV99CE", "CE", 23900, EXPIRY, true);
    contract("DERIV99PE", "PE", 23900, EXPIRY, true);
    contract("DERIV99XCE", "CE", 24000, EXPIRY, false); // incomplete → coverage-gated out
    contract("DERIV99NEXT", "CE", 23900, EXPIRY2, true); // a later expiry for frontExpiry()
    contract("DERIV99FUT", "FUT", 0, EXPIRY, true); // the front future for the spine fallback

    // CE: bucket1 last(oi)=1000 @09:19, bucket2 last(oi)=1500 @09:24 → oi_change +500
    candle("DERIV99CE", ist("09:17"), "48", 900, 10);
    candle("DERIV99CE", ist("09:19"), "50", 1000, 20);
    candle("DERIV99CE", ist("09:22"), "55", 1300, 30);
    candle("DERIV99CE", ist("09:24"), "60", 1500, 45);
    // PE: bucket1 last(oi)=2000, bucket2 last(oi)=1800 → oi_change -200. Premium kept realistic for a
    // 50-pt-OTM 2-day put (forward ~23950) so the read-time IV solve converges inside the bracket.
    candle("DERIV99PE", ist("09:19"), "12", 2000, 12);
    candle("DERIV99PE", ist("09:24"), "10", 1800, 25);
    // incomplete strike — must NOT appear
    candle("DERIV99XCE", ist("09:19"), "5", 9999, 1);
    // front-future candles → the derived spot proxy (last close per bucket).
    candle("DERIV99FUT", ist("09:19"), "23950", 0, 100);
    candle("DERIV99FUT", ist("09:24"), "23970", 0, 120);

    // equivalent captured snapshots (same oi at the bucket-closing ts) for the parity assertion
    snapshot("DERIV99CE", "CE", 23900, ist("09:19"), 1000);
    snapshot("DERIV99CE", "CE", 23900, ist("09:24"), 1500);
    snapshot("DERIV99PE", "PE", 23900, ist("09:19"), 2000);
    snapshot("DERIV99PE", "PE", 23900, ist("09:24"), 1800);
  }

  private static String key(OptionsSnapshotReader.StrikePoint p) {
    // JDBC returns the time_bucket as a UTC-offset OffsetDateTime; key on the instant so the
    // representation (offset) can't matter.
    return p.bucket().toInstant() + "|" + p.strike().stripTrailingZeros().toPlainString() + "|" + p.optionType();
  }

  private static String at(OffsetDateTime bucketStart, String leg) {
    return bucketStart.toInstant() + "|23900|" + leg;
  }

  @Test
  void bucketsOiAndLtpWithLagOiChangeAndCoverageGate() {
    List<OptionsSnapshotReader.StrikePoint> s =
        reader.series(EU, EXPIRY, OiInterval.M5, ist("09:00"), ist("15:30"));

    // 1 covered strike × 2 legs × 2 buckets = 4 points; the incomplete 24000 strike is gated out.
    assertThat(s).hasSize(4);
    assertThat(s).allSatisfy(p -> assertThat(p.strike().intValue()).isEqualTo(23900));

    Map<String, OptionsSnapshotReader.StrikePoint> byKey =
        s.stream().collect(Collectors.toMap(CandleDerivedChainReaderIntegrationTest::key, Function.identity()));
    OffsetDateTime b1 = ist("09:15"); // 5-min bucket start (09:15-09:20)
    OffsetDateTime b2 = ist("09:20"); // (09:20-09:25)

    // bucketed last(oi)/last(close); first bucket oi_change null, second = bucket-over-bucket diff.
    var ce1 = byKey.get(at(b1, "CE"));
    var ce2 = byKey.get(at(b2, "CE"));
    assertThat(ce1.oi()).isEqualTo(1000L);
    assertThat(ce1.oiChange()).isNull();
    assertThat(ce1.ltp()).isEqualByComparingTo("50");
    assertThat(ce2.oi()).isEqualTo(1500L);
    assertThat(ce2.oiChange()).isEqualTo(500L);
    assertThat(ce2.ltp()).isEqualByComparingTo("60");
    assertThat(byKey.get(at(b2, "PE")).oiChange()).isEqualTo(-200L);

  }

  @Test
  void recomputesAtmBandIvFromPremiumUsingTheFutureForward() {
    // forward (future close) 23950/23970; the 23900 PE is OTM-by-forward → solvable from its premium.
    var s = reader.series(EU, EXPIRY, OiInterval.M5, ist("09:00"), ist("15:30"));
    var pe = s.stream()
        .filter(p -> "PE".equals(p.optionType()) && p.bucket().toInstant().equals(ist("09:20").toInstant()))
        .findFirst().orElseThrow();
    assertThat(pe.iv()).as("ATM-band PE IV solved from candle premium").isNotNull();
    assertThat(pe.iv().doubleValue()).isBetween(0.0001, 5.0); // a sane vol, inside the solver bracket
  }

  @Test
  void latestSlicesTheNewestBucketAndLatestPairTheTwoNewest() {
    LocalDate date = LocalDate.of(2099, 1, 13);
    var latest = reader.latest(EU, EXPIRY, OiInterval.M5, date);
    // newest bucket (09:20-09:25) only → CE+PE at 23900 with the later oi.
    assertThat(latest).hasSize(2);
    assertThat(latest).allSatisfy(p -> assertThat(p.bucket().toInstant()).isEqualTo(ist("09:20").toInstant()));
    assertThat(latest.stream().filter(p -> "CE".equals(p.optionType())).findFirst().orElseThrow().oi())
        .isEqualTo(1500L);

    var pair = reader.latestPair(EU, EXPIRY, OiInterval.M5, date);
    assertThat(pair).hasSize(4); // both buckets × CE/PE
  }

  @Test
  void strikeSeriesFiltersToOneStrikeAndEodSeriesRollsUpPremiumOhlc() {
    var only23900 = reader.strikeSeries(EU, EXPIRY, new java.math.BigDecimal("23900"), OiInterval.M5, ist("09:00"), ist("15:30"));
    assertThat(only23900).hasSize(4).allSatisfy(p -> assertThat(p.strike().intValue()).isEqualTo(23900));
    assertThat(reader.strikeSeries(EU, EXPIRY, new java.math.BigDecimal("99999"), OiInterval.M5, ist("09:00"), ist("15:30"))).isEmpty();

    var eod = reader.eodSeries(EU, EXPIRY, ist("00:00"), ist("23:59"));
    var ce = eod.stream().filter(r -> "CE".equals(r.optionType())).findFirst().orElseThrow();
    assertThat(ce.open()).isEqualByComparingTo("48"); // first 1m close of the day
    assertThat(ce.high()).isEqualByComparingTo("60");
    assertThat(ce.close()).isEqualByComparingTo("60"); // last 1m close
    assertThat(ce.oiClose()).isEqualTo(1500L);
  }

  @Test
  void derivedOiMatchesCapturedSnapshotOi() {
    Map<String, Long> derived =
        reader.series(EU, EXPIRY, OiInterval.M5, ist("09:00"), ist("15:30")).stream()
            .collect(Collectors.toMap(CandleDerivedChainReaderIntegrationTest::key, OptionsSnapshotReader.StrikePoint::oi));
    Map<String, Long> captured =
        snapshotReader.series(DISPLAY, EXPIRY, OiInterval.M5, ist("09:00"), ist("15:30")).stream()
            .collect(Collectors.toMap(CandleDerivedChainReaderIntegrationTest::key, OptionsSnapshotReader.StrikePoint::oi));
    // same time_bucket + last(oi) semantics → the OI a backtest reads is identical either source.
    assertThat(derived).containsAllEntriesOf(captured);
  }

  @Test
  void frontExpiryPicksNearestCompleteOnOrAfterSession() {
    assertThat(reader.frontExpiry(EU, LocalDate.of(2099, 1, 1))).isEqualTo(EXPIRY);
    assertThat(reader.frontExpiry(EU, EXPIRY.plusDays(1))).isEqualTo(EXPIRY2);
    assertThat(reader.frontExpiry("NOPE", LocalDate.of(2099, 1, 1))).isNull();
  }

  @Test
  void seriesCarriesTheFutureCloseAsSpotProxy() {
    var s = reader.series(EU, EXPIRY, OiInterval.M5, ist("09:00"), ist("15:30"));
    // bucket1 (09:15-09:20) spot = future last close 23950; bucket2 (09:20-09:25) = 23970.
    var b1 = s.stream().filter(p -> p.bucket().toInstant().equals(ist("09:15").toInstant())).findFirst().orElseThrow();
    var b2 = s.stream().filter(p -> p.bucket().toInstant().equals(ist("09:20").toInstant())).findFirst().orElseThrow();
    assertThat(b1.spot()).isEqualByComparingTo("23950");
    assertThat(b2.spot()).isEqualByComparingTo("23970");
  }

  @Test
  void sessionStatsGradesPremiumOhlcPerStrike() {
    var ss = reader.sessionStats(EU, EXPIRY, LocalDate.of(2099, 1, 13), 5);
    var ce = ss.stream()
        .filter(x -> "CE".equals(x.optionType()) && x.strike().intValue() == 23900)
        .findFirst().orElseThrow();
    // CE bucketed ltp: bucket1 last 50 (open), bucket2 last 60 → high 60, low 50, last 60.
    assertThat(ce.open()).isEqualByComparingTo("50");
    assertThat(ce.high()).isEqualByComparingTo("60");
    assertThat(ce.low()).isEqualByComparingTo("50");
    assertThat(ce.last()).isEqualByComparingTo("60");
  }

  @Test
  void frontFutureResolvesNearestCompleteFut() {
    assertThat(reader.frontFuture(EU, LocalDate.of(2099, 1, 1)))
        .extracting(CandleDerivedChainReader.FrontFuture::tradingsymbol)
        .isEqualTo("DERIV99FUT");
    assertThat(reader.frontFuture("NOPE", LocalDate.of(2099, 1, 1))).isNull();
  }

  @Test
  void mapsIndexDisplayNameToExpiredUnderlyingSymbol() {
    assertThat(CandleDerivedChainReader.expiredUnderlying("NIFTY 50")).isEqualTo("NIFTY");
    assertThat(CandleDerivedChainReader.expiredUnderlying("SENSEX")).isEqualTo("SENSEX");
    assertThat(CandleDerivedChainReader.expiredUnderlying("NIFTY 500")).isNull();
  }
}
