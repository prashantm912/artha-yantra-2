package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Macro;
import in.arthayantra.strategysignal.scalper.ScalperGateContext.Oi;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Locks the §12.2 mapping of market-data analytics JSON into the local scalper OI/macro records,
 * and the conservative-default contract: an upstream miss must degrade to a value that never
 * confirms a side (NEUTRAL quadrant / null soft-numeric / 0 breadth), never throw.
 */
class MarketOiClientTest {

  private static final String UNDERLYING = "NIFTY 50";
  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 25);
  private static final LocalDate TRADE_DATE = LocalDate.of(2026, 6, 20);
  // 2026-06-30 is the June MONTHLY index expiry (the last June weekly expiry); 2026-06-23 is weekly-only.
  private static final LocalDate MONTHLY_EXPIRY = LocalDate.of(2026, 6, 30);
  private static final LocalDate NON_MONTHLY = LocalDate.of(2026, 6, 23);
  /** 2026-07-28 is the July NSE monthly index expiry — a SECOND key for the once-per-day log guard. */
  private static final LocalDate JULY_MONTHLY = LocalDate.of(2026, 7, 28);
  private static final String BASIS_JSON =
      "{\"contracts\":[{\"expiry\":\"2026-06-25\",\"basisAbsolute\":\"10.5\"}]}";

  private MockRestServiceServer server;
  private MarketOiClient client;

  private void wire() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    client =
        new MarketOiClient(
            builder, new ObjectMapper(), MarketCalendar.nse(),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            "http://market-data:8081");
  }

  private void stub(String pathFragment, String json) {
    server
        .expect(ExpectedCount.once(), requestTo(containsString(pathFragment)))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  @Test
  void mapsOiHalfFromAnalyticsEndpoints() {
    wire();
    // underlying chain read: SHORT_COVERING (price↑, OI↓ — shorts exiting → bullish)
    stub("/api/v1/market/options/spurt", "{\"summary\":{\"interpretation\":\"SHORT_COVERING\"}}");
    // futures grid: the NEAREST expiry leg decides; far leg must be ignored
    stub(
        "/api/v1/market/futures/banks",
        "{\"items\":["
            + "{\"expiry\":\"2026-07-30\",\"interpretation\":\"LONG_UNWINDING\",\"basis\":\"30\"},"
            + "{\"expiry\":\"2026-06-25\",\"interpretation\":\"LONG_BUILDUP\",\"basis\":\"10\"}]}");
    // `sentimentLevelPct` is the measurement-only LEVEL sibling market-data already publishes beside
    // the ΔOI-FLOW `sentimentPct` the gates consume — asserted below to prove it reaches the Oi record.
    stub(
        "/api/v1/market/options/active-strikes",
        "{\"sentimentPct\":\"12.5\",\"sentimentLevelPct\":\"-33.33\",\"items\":[]}");
    // trending: latest point PE 200 / CE 100 of 300 total → (200-100)*100/300 = 33.3333.
    // The rolling window MUST be pinned (buckets=20) — the bare endpoint now serves the full
    // session, which would silently make the first-vs-last derivations session-cumulative.
    stub(
        "/api/v1/market/options/trending?name=NIFTY%2050&expiry=2026-06-25&buckets=20",
        "{\"items\":[{\"ceOi\":50,\"peOi\":50},{\"ceOi\":100,\"peOi\":200}]}");
    stub(
        "/api/v1/market/futures/term-structure",
        "{\"contracts\":["
            + "{\"expiry\":\"2026-07-30\",\"basisAbsolute\":\"25\"},"
            + "{\"expiry\":\"2026-06-25\",\"basisAbsolute\":\"10.5\"}]}");

    Oi oi = client.oi(UNDERLYING, EXPIRY, NON_MONTHLY);

    assertThat(oi.underlying()).isEqualTo(OiQuadrant.SHORT_COVERING);
    assertThat(oi.futures()).isEqualTo(OiQuadrant.LONG_BUILDUP); // nearest expiry, not the far leg
    assertThat(oi.sentimentPct()).isEqualByComparingTo("12.5");
    // The measurement-only sibling lands on its OWN field — opposite in sign to the flow operand on
    // this payload, so a mis-wire that copied one onto the other could not pass both assertions.
    assertThat(oi.sentimentLevelPct()).isEqualByComparingTo("-33.33");
    assertThat(oi.trendingPeMinusCePct()).isEqualByComparingTo("33.3333");
    assertThat(oi.futuresBasis()).isEqualByComparingTo("10.5"); // front leg basis
    server.verify();
  }

  @Test
  void suppressesChainOiOnAMonthlyExpiryDayKeepingOnlyTheBasis() {
    wire();
    // S24 caveat: on the monthly expiry the 4 chain-OI reads must NOT fire (expiring writers unwind).
    // Only the price-derived term-structure basis is read. Asserted by NOT stubbing the OI endpoints —
    // MockRestServiceServer fails on any unexpected request, so an OI call here would error the test.
    stub("/api/v1/market/futures/term-structure", "{\"contracts\":[{\"expiry\":\"2026-06-25\",\"basisAbsolute\":\"10.5\"}]}");

    Oi oi = client.oi(UNDERLYING, EXPIRY, MONTHLY_EXPIRY);

    // Every OI input degrades to its inert default — non-confirming for either side.
    assertThat(oi.underlying()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(oi.futures()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(oi.sentimentPct()).isNull();
    // …including the measurement-only sibling: a suppressed chain gives no honest LEVEL either, so
    // the shadow correctly records "no verdict" rather than a reading off unwinding writers.
    assertThat(oi.sentimentLevelPct()).isNull();
    assertThat(oi.trendingPeMinusCePct()).isNull();
    assertThat(oi.ceOiDelta()).isNull();
    assertThat(oi.peOiDelta()).isNull();
    assertThat(oi.callPutDeltaImbalancePct()).isNull();
    assertThat(oi.sentimentSlope()).isNull();
    assertThat(oi.spurtOiPct()).isNull();
    assertThat(oi.spurtPricePct()).isNull();
    assertThat(oi.crossedThisWindow()).isFalse();
    assertThat(oi.gapWidening()).isFalse();
    // The price-derived basis is KEPT so the basis dot still works.
    assertThat(oi.futuresBasis()).isEqualByComparingTo("10.5");
    // PROVENANCE: the snapshot records that it came from the suppression branch. Everything above
    // is byte-identical to a four-failed-reads snapshot, so without this flag nothing downstream
    // can tell a by-design suppression from an outage (see the pair test below).
    assertThat(oi.monthlyExpirySuppressed()).isTrue();
    server.verify(); // only the one basis request fired
  }

  /**
   * ⚠️ THE AMBIGUITY, AND ITS RESOLUTION, in one test — both halves through the real producer.
   *
   * <p>First half: a monthly-expiry suppression and a NON-expiry day on which all four OI reads
   * fail produce {@code Oi} records equal in every operand field. That is not incidental; it is the
   * documented S24 design ("exactly as if the OI endpoints were unavailable"), and it is why two
   * readers built a live-regression theory out of 2026-08-25's rows.
   *
   * <p>Second half: the two therefore reach {@link SentimentLevelShadow} identical, and must still
   * come out carrying DIFFERENT reason codes. A reason derived from the operands — rather than from
   * recorded provenance — cannot pass this, because there is nothing in the operands to derive it
   * from.
   */
  @Test
  void aSuppressedDayAndAFailedReadDayAreOperandIdenticalButReasonDistinct() {
    wire();
    stub("/api/v1/market/futures/term-structure", BASIS_JSON);
    Oi suppressed = client.oi(UNDERLYING, EXPIRY, MONTHLY_EXPIRY);
    server.verify();

    // A normal trading day on which every OI endpoint 500s. The basis read still succeeds, so the
    // two snapshots differ in nothing an operand-reader could see.
    wire();
    for (String path :
        new String[] {
          "/api/v1/market/options/spurt",
          "/api/v1/market/futures/banks",
          "/api/v1/market/options/active-strikes",
          "/api/v1/market/options/trending"
        }) {
      server
          .expect(ExpectedCount.once(), requestTo(containsString(path)))
          .andRespond(withServerError());
    }
    stub("/api/v1/market/futures/term-structure", BASIS_JSON);
    Oi failedReads = client.oi(UNDERLYING, EXPIRY, NON_MONTHLY);
    server.verify();

    // The two are indistinguishable on every operand — asserted, not assumed.
    assertThat(withoutProvenance(suppressed)).isEqualTo(withoutProvenance(failedReads));
    assertThat(suppressed.monthlyExpirySuppressed()).isTrue();
    assertThat(failedReads.monthlyExpirySuppressed()).isFalse();

    // …and that one recorded bit is what lets the persisted row say which world it came from.
    assertThat(SentimentLevelShadow.of(suppressed, CE).reason())
        .isEqualTo(SentimentLevelShadow.Reason.MONTHLY_EXPIRY_SUPPRESSED);
    assertThat(SentimentLevelShadow.of(failedReads, CE).reason())
        .isEqualTo(SentimentLevelShadow.Reason.LEVEL_UNAVAILABLE);
  }

  /** The same snapshot with the provenance bit cleared — so record equality compares OPERANDS only. */
  private static Oi withoutProvenance(Oi oi) {
    return new Oi(
        oi.underlying(), oi.futures(), oi.sentimentPct(), oi.trendingPeMinusCePct(),
        oi.futuresBasis(), oi.ceOiDelta(), oi.peOiDelta(), oi.callPutDeltaImbalancePct(),
        oi.crossedThisWindow(), oi.gapWidening(), oi.sentimentSlope(), oi.spurtOiPct(),
        oi.spurtPricePct(), oi.oiDivergencePct(), oi.sentimentLevelPct());
  }

  /**
   * The suppression must be AUDIBLE. It was {@code log.debug}, and nothing configures this service
   * above the Spring default of INFO, so the branch fired ~1,100 times on 2026-08-25 and {@code
   * docker logs | grep monthly-expiry} returned 0 on the very day two people were hunting for it.
   *
   * <p>Two properties, and the test needs both: the line is INFO (a DEBUG line is never emitted in
   * production at all), and it fires ONCE per (day, underlying) rather than per call — {@code oi()}
   * runs per strategy per bar, so an unguarded line would bury every other INFO under four figures
   * of identical text and would be turned off again by the next person to read the logs.
   */
  @Test
  void theSuppressionIsAnnouncedAtInfoOncePerDayAndUnderlying() {
    wire();
    server
        .expect(
            ExpectedCount.manyTimes(),
            requestTo(containsString("/api/v1/market/futures/term-structure")))
        .andRespond(withSuccess(BASIS_JSON, MediaType.APPLICATION_JSON));

    Logger oiLog = (Logger) LoggerFactory.getLogger(MarketOiClient.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    oiLog.addAppender(logs);
    try {
      client.oi(UNDERLYING, EXPIRY, MONTHLY_EXPIRY); // announced
      client.oi(UNDERLYING, EXPIRY, MONTHLY_EXPIRY); // same key — silent
      client.oi(UNDERLYING, EXPIRY, JULY_MONTHLY); // a new day — announced again

      List<ILoggingEvent> announced =
          logs.list.stream()
              .filter(e -> e.getFormattedMessage().contains("monthly-expiry"))
              .toList();

      // No appender filtering is configured for unit tests, so a DEBUG line WOULD be captured here
      // — which is what makes the level assertion meaningful rather than vacuous.
      assertThat(announced)
          .as("the suppression line must be INFO; at DEBUG it is never emitted in production")
          .isNotEmpty()
          .allMatch(e -> e.getLevel() == Level.INFO);
      assertThat(announced)
          .as("once per (day, underlying): 2 keys over 3 calls")
          .hasSize(2);
      assertThat(announced.get(0).getFormattedMessage())
          .contains("2026-06-30")
          .contains(UNDERLYING)
          .contains("MONTHLY_EXPIRY_SUPPRESSED");
    } finally {
      oiLog.detachAppender(logs);
    }
  }

  /**
   * The ordinary mapping path leaves the provenance bit CLEAR. Without this, a flag hard-wired to
   * true would pass every suppression assertion above and quietly relabel every normal session as a
   * monthly expiry.
   */
  @Test
  void anOrdinaryDayIsNotMarkedSuppressed() {
    wire();
    stub("/api/v1/market/options/spurt", "{\"summary\":{\"interpretation\":\"SHORT_COVERING\"}}");
    stub("/api/v1/market/futures/banks", "{\"items\":[]}");
    stub(
        "/api/v1/market/options/active-strikes",
        "{\"sentimentPct\":\"12.5\",\"sentimentLevelPct\":\"-33.33\",\"items\":[]}");
    stub("/api/v1/market/options/trending", "{\"items\":[]}");
    stub("/api/v1/market/futures/term-structure", BASIS_JSON);

    Oi oi = client.oi(UNDERLYING, EXPIRY, NON_MONTHLY);

    assertThat(oi.monthlyExpirySuppressed()).isFalse();
    assertThat(SentimentLevelShadow.of(oi, CE).reason())
        .isEqualTo(SentimentLevelShadow.Reason.COMPUTED);
    server.verify();
  }

  @Test
  void trend60mDirReadsTheSignOfThe60mOiBuild() {
    wire();
    // E2 M7: over the 60m window CE OI builds faster than PE (ceBuild 300 vs peBuild 150) → CE-heavy
    // build = bearish → dir < 0. One focused /options/trending?interval=60m read, window pinned.
    stub(
        "interval=60m&buckets=20",
        "{\"items\":[{\"ceOi\":100,\"peOi\":100},{\"ceOi\":400,\"peOi\":250}]}");
    assertThat(client.trend60mDir(UNDERLYING, EXPIRY, NON_MONTHLY)).isEqualTo(-1);
    server.verify();
  }

  @Test
  void trend60mDirIsZeroOnAMonthlyExpiryWithoutFiring() {
    wire();
    // S24: the chain-OI is corrupt on a monthly index expiry → no 60m fetch fires, returns 0
    // (unknown → the M7 gate fail-opens). Nothing is stubbed, so any request would error the test.
    assertThat(client.trend60mDir(UNDERLYING, EXPIRY, MONTHLY_EXPIRY)).isEqualTo(0);
    server.verify();
  }

  @Test
  void derivePremiumSkewIsTheSignedAtmCeVsPePremiumGap() throws Exception {
    wire(); // initialises the client (no HTTP call — derivePremiumSkew is a pure parse).
    // E7: the ATM (nearest spot=100) carries CE ltp 120 vs PE ltp 80 → (120-80)/80×100 = +50% (CE
    // is the richer side). Off-ATM rows are ignored.
    var chain =
        new ObjectMapper()
            .readTree(
                "{\"spot\":100,\"rows\":["
                    + "{\"strike\":95,\"ce\":{\"ltp\":150},\"pe\":{\"ltp\":60}},"
                    + "{\"strike\":100,\"ce\":{\"ltp\":120},\"pe\":{\"ltp\":80}},"
                    + "{\"strike\":105,\"ce\":{\"ltp\":90},\"pe\":{\"ltp\":110}}]}");
    assertThat(client.derivePremiumSkew(chain)).isEqualByComparingTo("50");
  }

  @Test
  void derivePremiumSkewIsNullWhenAnAtmPremiumIsAbsent() throws Exception {
    wire(); // initialises the client (no HTTP call).
    // a missing PE ltp on the ATM row → null → the premium-skew dot degrades to neutral (never blocks).
    var chain =
        new ObjectMapper()
            .readTree("{\"spot\":100,\"rows\":[{\"strike\":100,\"ce\":{\"ltp\":120},\"pe\":{}}]}");
    assertThat(client.derivePremiumSkew(chain)).isNull();
  }

  @Test
  void deriveVixReadsTheLevelAndRisingDirection() throws Exception {
    wire();
    var om = new ObjectMapper();
    // change > 0 → VIX rising (favours PE)
    var up = client.deriveVix(om.readTree("{\"ltp\":\"15.2\",\"change\":\"0.40\"}"));
    assertThat(up.level()).isEqualByComparingTo("15.2");
    assertThat(up.rising()).isTrue();
    // change < 0 → falling (favours CE)
    assertThat(client.deriveVix(om.readTree("{\"ltp\":\"14.0\",\"change\":\"-0.10\"}")).rising()).isFalse();
    // change 0 (or absent) → direction unknown (null → the vix gate stays non-blocking)
    assertThat(client.deriveVix(om.readTree("{\"ltp\":\"14.0\",\"change\":\"0\"}")).rising()).isNull();
    assertThat(client.deriveVix(om.readTree("{}")).level()).isNull();
  }

  @Test
  void deriveDowUpReadsTheSignedDirection() throws Exception {
    wire();
    var om = new ObjectMapper();
    assertThat(client.deriveDowUp(om.readTree("{\"direction\":1}"))).isTrue(); // up → CE
    assertThat(client.deriveDowUp(om.readTree("{\"direction\":-1}"))).isFalse(); // down → PE
    assertThat(client.deriveDowUp(om.readTree("{\"direction\":0}"))).isNull(); // flat → neutral
    assertThat(client.deriveDowUp(om.readTree("{}"))).isNull(); // absent → unknown
  }

  /**
   * The EOD-sourced reads must ask for the last SETTLED session, never the live bar's own date.
   *
   * <p>NSE publishes FII/participant and bhavcopy data after the close, so a read keyed on today
   * returns an EMPTY set for the whole session — which is precisely what happened live: all three
   * engine call sites passed the bar date, `/fii-dii/long-short?from=<today>` answered
   * `{"items":[]}`, and the `fii` dot was null on every bar of every session.
   *
   * <p>The bar date here is Monday 2026-06-22, so the expected read date is Friday 2026-06-19 —
   * June 2026 carries exactly one NSE holiday (2026-06-26), so that pair holds independently of the
   * resolver being tested. Each stub matches on the DATE IN THE URL, so asking for the wrong day
   * fails to match and the test fails.
   */
  @Test
  void eodReadsAskForTheLastSettledSessionNotTheLiveBarDate() {
    wire();
    LocalDate monday = LocalDate.of(2026, 6, 22);

    // The three EOD-sourced reads, each pinned to the SETTLED session (Friday), not the bar date.
    stub(
        "fii-dii/long-short?from=2026-06-19",
        "{\"items\":[{\"fiiLong\":70,\"fiiShort\":30}]}");
    stub("fii-dii/bias?date=2026-06-19", "{\"biasSign\":-1,\"bias\":\"BEAR\"}");

    // Everything else macro() touches — not under test, stubbed so the fan-out completes.
    stub("/api/v1/market/options/iv-history", "{\"currentIv\":\"0.14\",\"insufficientHistory\":true}");
    stub("/api/v1/market/breadth/live", "{\"summary\":{\"advances\":35,\"declines\":12}}");
    stub("/api/v1/market/options/chain", "{\"spot\":\"20000\",\"rows\":[]}");
    stub("/api/v1/market/equity/index-contribution", "{\"indexChangePct\":\"0.5\"}");
    stub("/api/v1/market/options/active-strikes", "{\"activeStrikeIvSeries\":[]}");
    stub("/api/v1/market/vix", "{\"ltp\":\"14.5\",\"change\":\"-0.30\"}");
    stub("/api/v1/market/global/dow", "{\"direction\":1}");

    Macro m = client.macro(UNDERLYING, monday, EXPIRY);

    server.verify(); // every date-pinned stub was hit — i.e. no read asked for the bar date
    assertThat(m.fiiLongPct())
        .as("the dot the live defect left null on every bar")
        .isEqualByComparingTo("70");
    assertThat(m.fiiBiasSign()).isEqualByComparingTo("-1");
    assertThat(m.advances()).isEqualTo(35);
  }

  @Test
  void mapsMacroHalfAndScalesIvRankToHundred() {
    wire();
    // rank is a 0..1 fraction upstream; must surface ×100 so the scorer's <50 gate is meaningful
    stub(
        "/api/v1/market/options/iv-history",
        "{\"currentIv\":\"0.14\",\"rank\":\"0.30\",\"percentile\":28,\"insufficientHistory\":false}");
    stub(
        "/api/v1/market/breadth",
        "{\"summary\":{\"advances\":35,\"declines\":12,\"unchanged\":3,\"total\":50}}");
    stub(
        "/api/v1/market/fii-dii/long-short",
        "{\"items\":[{\"fiiLong\":60,\"fiiShort\":40},{\"fiiLong\":70,\"fiiShort\":30}]}");
    // §A4: macro now also reads the chain for the 6-strike IV pair — a chain with <6 strikes leaves
    // the pair null without disturbing the IV/rank/breadth/FII assertions below.
    stub("/api/v1/market/options/chain", "{\"spot\":\"20000\",\"rows\":[]}");
    stub("/api/v1/market/equity/index-contribution", "{\"indexChangePct\":\"0.5\"}");
    // E4: macro now also reads the active-strike IV series for the per-strike CE/PE IV slope.
    stub(
        "/api/v1/market/options/active-strikes",
        "{\"activeStrikeIvSeries\":[{\"ceIv\":\"0.12\",\"peIv\":\"0.15\"},"
            + "{\"ceIv\":\"0.16\",\"peIv\":\"0.13\"}]}");
    // E3: macro now also reads the INDIA VIX quote + the Dow global cue (direction +1 up / −1 down).
    stub("/api/v1/market/vix", "{\"ltp\":\"14.5\",\"change\":\"-0.30\"}");
    stub("/api/v1/market/global/dow", "{\"direction\":1}");
    // E3 §3.3: macro now also reads the combined FII participant bias sign (+1 bull / -1 bear / 0).
    stub("/api/v1/market/fii-dii/bias", "{\"biasSign\":1,\"bias\":\"BULL\"}");

    Macro m = client.macro(UNDERLYING, TRADE_DATE, EXPIRY);

    assertThat(m.atmIv()).isEqualByComparingTo("0.14");
    assertThat(m.constituentBias()).isEqualByComparingTo("0.5"); // /equity/index-contribution indexChangePct
    assertThat(m.ivRank()).isEqualByComparingTo("30"); // 0.30 × 100
    assertThat(m.ceIvSlope()).isEqualByComparingTo("0.04"); // 0.16 − 0.12 (CE-strike IV rising)
    assertThat(m.peIvSlope()).isEqualByComparingTo("-0.02"); // 0.13 − 0.15 (PE-strike IV falling)
    assertThat(m.ceIvAvg6()).isNull(); // <6 strikes → no IV pair
    assertThat(m.peIvAvg6()).isNull();
    assertThat(m.advances()).isEqualTo(35);
    assertThat(m.declines()).isEqualTo(12);
    assertThat(m.fiiLongPct()).isEqualByComparingTo("70"); // 70 / (70+30) × 100
    assertThat(m.vixLevel()).isEqualByComparingTo("14.5"); // /vix ltp
    assertThat(m.vixRising()).isFalse(); // change −0.30 < 0 → VIX falling (favours CE)
    assertThat(m.dowUp()).isTrue(); // /global/dow direction +1 → Dow up (favours CE)
    assertThat(m.fiiBiasSign()).isEqualByComparingTo("1"); // /fii-dii/bias biasSign +1 → FII bull
    server.verify();
  }

  @Test
  void nullIvRankWhenHistoryInsufficient() {
    wire();
    stub(
        "/api/v1/market/options/iv-history",
        "{\"currentIv\":\"0.18\",\"rank\":null,\"insufficientHistory\":true}");
    stub("/api/v1/market/breadth", "{\"summary\":{\"advances\":5,\"declines\":40}}");
    stub("/api/v1/market/fii-dii/long-short", "{\"items\":[]}");
    stub("/api/v1/market/options/chain", "{\"spot\":\"20000\",\"rows\":[]}");
    stub("/api/v1/market/equity/index-contribution", "{}");
    stub("/api/v1/market/options/active-strikes", "{}"); // empty series → null slopes
    stub("/api/v1/market/vix", "{}");
    stub("/api/v1/market/global/dow", "{}"); // absent quote → null level + unknown direction
    stub("/api/v1/market/fii-dii/bias", "{}"); // absent → null biasSign → fii-dii-gate degrades to pass

    Macro m = client.macro(UNDERLYING, TRADE_DATE, EXPIRY);

    assertThat(m.ivRank()).isNull(); // not 0 — "unknown", so the iv_rank dot stays unconfirmed
    assertThat(m.constituentBias()).isNull(); // empty contribution envelope
    assertThat(m.ceIvSlope()).isNull(); // empty active-strike series → no slope
    assertThat(m.fiiLongPct()).isNull(); // empty envelope
  }

  @Test
  void flattensTheNearestChainIntoBothSideCandidates() {
    wire();
    stub(
        "/api/v1/market/options/chain",
        "{\"underlying\":\"NIFTY 50\",\"expiry\":\"2026-06-25\",\"spot\":\"20000\",\"forward\":\"20040\","
            + "\"rows\":["
            + "{\"strike\":\"19900\",\"ce\":{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY19900CE\",\"ltp\":\"180\",\"iv\":\"0.14\"},"
            + "\"pe\":{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY19900PE\",\"ltp\":\"60\",\"iv\":\"0.15\"}},"
            // a leg with null iv is dropped (cannot price it)
            + "{\"strike\":\"20000\",\"ce\":{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY20000CE\",\"ltp\":\"110\",\"iv\":null},"
            + "\"pe\":{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY20000PE\",\"ltp\":\"120\",\"iv\":\"0.13\"}}]}");

    MarketOiClient.ChainSnapshot snap = client.chain(UNDERLYING).orElseThrow();

    assertThat(snap.expiry()).isEqualTo(EXPIRY);
    assertThat(snap.basis()).isEqualByComparingTo("40"); // forward 20040 − spot 20000
    // 4 legs present, 1 (20000 CE, null iv) dropped → 3 candidates
    assertThat(snap.candidates()).hasSize(3);
    assertThat(snap.candidates())
        .anySatisfy(
            c -> {
              assertThat(c.tradingsymbol()).isEqualTo("NIFTY19900CE");
              assertThat(c.strike()).isEqualByComparingTo("19900");
              assertThat(c.type()).isEqualTo(in.arthayantra.black76.Black76.OptionType.CE);
              assertThat(c.ltp()).isEqualByComparingTo("180");
              assertThat(c.iv()).isEqualByComparingTo("0.14");
            });
    server.verify();
  }

  @Test
  void chainIsEmptyWhenUpstreamFails() {
    wire();
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/market/options/chain")))
        .andRespond(withServerError());

    assertThat(client.chain(UNDERLYING)).isEmpty();
  }

  @Test
  void degradesEveryOiPrimitiveToConservativeDefaultOnUpstreamFailure() {
    wire();
    for (String path :
        new String[] {
          "/api/v1/market/options/spurt",
          "/api/v1/market/futures/banks",
          "/api/v1/market/options/active-strikes",
          "/api/v1/market/options/trending",
          "/api/v1/market/futures/term-structure"
        }) {
      server.expect(ExpectedCount.once(), requestTo(containsString(path))).andRespond(withServerError());
    }

    Oi oi = client.oi(UNDERLYING, EXPIRY, NON_MONTHLY);

    // NEUTRAL is bullish()==false AND bearish()==false → cannot confirm either side
    assertThat(oi.underlying()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(oi.futures()).isEqualTo(OiQuadrant.NEUTRAL);
    assertThat(oi.underlying().bullish()).isFalse();
    assertThat(oi.underlying().bearish()).isFalse();
    assertThat(oi.sentimentPct()).isNull();
    assertThat(oi.trendingPeMinusCePct()).isNull();
    assertThat(oi.futuresBasis()).isNull();
    server.verify();
  }

  @Test
  void mapsTheOpenHighPerStrikeFootprint() {
    wire();
    stub(
        "/api/v1/market/options/strike-session-stats",
        "{\"atmStrike\":\"20000\",\"items\":["
            + "{\"strike\":\"19900\",\"optionType\":\"CE\",\"ohMark\":true,\"olMark\":false,"
            + "\"last\":\"90\",\"open\":\"100\",\"high\":\"100\",\"declineVolume\":60000,"
            + "\"fallPctFromPrevClose\":\"-12.5\"},"
            // a PE leg with no declineVolume (null) and a string-typed last
            + "{\"strike\":\"19900\",\"optionType\":\"PE\",\"ohMark\":false,\"olMark\":true,"
            + "\"last\":\"40\",\"open\":\"40\",\"high\":\"45\",\"fallPctFromPrevClose\":\"5\"},"
            // a malformed row (bad optionType) must be skipped, not fatal
            + "{\"strike\":\"20000\",\"optionType\":\"XX\",\"ohMark\":true,\"olMark\":false}]}");

    MarketOiClient.OpenHighStats stats = client.openHighStats(UNDERLYING, EXPIRY, 3);

    assertThat(stats.atmStrike()).isEqualByComparingTo("20000");
    assertThat(stats.items()).hasSize(2); // the XX row dropped
    MarketOiClient.StrikeStat ce = stats.items().get(0);
    assertThat(ce.type()).isEqualTo(in.arthayantra.black76.Black76.OptionType.CE);
    assertThat(ce.strike()).isEqualByComparingTo("19900");
    assertThat(ce.ohMark()).isTrue();
    assertThat(ce.last()).isEqualByComparingTo("90");
    assertThat(ce.declineVolume()).isEqualTo(60000L);
    assertThat(ce.fallPctFromPrevClose()).isEqualByComparingTo("-12.5");
    MarketOiClient.StrikeStat pe = stats.items().get(1);
    assertThat(pe.olMark()).isTrue();
    assertThat(pe.declineVolume()).isNull(); // absent -> null
    server.verify();
  }

  @Test
  void openHighStatsDegradesToEmptyOnUpstreamFailure() {
    wire();
    server
        .expect(
            ExpectedCount.once(),
            requestTo(containsString("/api/v1/market/options/strike-session-stats")))
        .andRespond(withServerError());

    MarketOiClient.OpenHighStats stats = client.openHighStats(UNDERLYING, EXPIRY, 3);

    // empty footprint -> the OpenHighLowGate then blocks (a missing endpoint never yields a HIGH).
    assertThat(stats.atmStrike()).isNull();
    assertThat(stats.items()).isEmpty();
  }

  /**
   * A breadth outage must NOT fall through to the whole-NSE bhavcopy read.
   *
   * <p>The "advances &gt; 32" rule is a NIFTY-50-universe rule (32 of 50). `/breadth?date=` counts the
   * entire NSE EQ bhavcopy — thousands of names — so &gt;32 is satisfied by essentially any session and
   * would confirm BOTH sides at once, manufacturing entries and confluence-flip exits out of an outage.
   * That fallback was inert only because it was asked for TODAY (0/0 until the post-close bhavcopy
   * lands); giving it a settled date would have armed the scale mismatch. `/breadth/live` already falls
   * back within its own ~50-name universe, so a dead read degrades to 0/0 and confirms nothing.
   */
  @Test
  void aBreadthOutageDegradesToZeroInsteadOfTheWholeMarketBhavcopy() {
    wire();
    stub("/api/v1/market/options/iv-history", "{\"currentIv\":\"0.14\",\"rank\":\"0.5\"}");
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/market/breadth/live")))
        .andRespond(withServerError());
    // No /breadth?date= stub is registered: if the removed fallback ever returns, MockRestServiceServer
    // fails the request as unexpected rather than quietly serving whole-market counts.
    stub("/api/v1/market/fii-dii/long-short", "{\"items\":[]}");
    stub("/api/v1/market/options/chain", "{\"spot\":\"20000\",\"rows\":[]}");
    stub("/api/v1/market/equity/index-contribution", "{}");
    stub("/api/v1/market/options/active-strikes", "{}");
    stub("/api/v1/market/vix", "{}");
    stub("/api/v1/market/global/dow", "{}");
    stub("/api/v1/market/fii-dii/bias", "{}");

    Macro m = client.macro(UNDERLYING, TRADE_DATE, EXPIRY);

    assertThat(m.advances()).as("a dead breadth read must never confirm a side").isZero();
    assertThat(m.declines()).isZero();
  }

  @Test
  void breadthDefaultsToZeroZeroSoTheGateCannotConfirm() {
    wire();
    stub("/api/v1/market/options/iv-history", "{\"currentIv\":\"0.14\",\"rank\":\"0.5\"}");
    // F3.1: the single breadth read (the ~50-name constituent fold) is down.
    server
        .expect(ExpectedCount.once(), requestTo(containsString("/api/v1/market/breadth")))
        .andRespond(withServerError());
    stub("/api/v1/market/fii-dii/long-short", "{\"items\":[]}");
    stub("/api/v1/market/options/chain", "{\"spot\":\"20000\",\"rows\":[]}");
    stub("/api/v1/market/equity/index-contribution", "{}");
    stub("/api/v1/market/options/active-strikes", "{}");
    stub("/api/v1/market/vix", "{}");
    stub("/api/v1/market/global/dow", "{}");
    stub("/api/v1/market/fii-dii/bias", "{}");

    Macro m = client.macro(UNDERLYING, TRADE_DATE, EXPIRY);

    assertThat(m.advances()).isZero();
    assertThat(m.declines()).isZero();
    // ScalperGates.breadth: count > 32 → 0 > 32 is false for BOTH sides → no false confirmation
    assertThat(ScalperGates.breadth(m, in.arthayantra.black76.Black76.OptionType.CE).pass()).isFalse();
    assertThat(ScalperGates.breadth(m, in.arthayantra.black76.Black76.OptionType.PE).pass()).isFalse();
  }
}
