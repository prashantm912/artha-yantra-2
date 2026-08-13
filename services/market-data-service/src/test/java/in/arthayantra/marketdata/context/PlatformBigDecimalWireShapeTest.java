package in.arthayantra.marketdata.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.jackson.ArthaJacksonAutoConfiguration;
import in.arthayantra.marketdata.canary.BhavcopyCloseCanary;
import in.arthayantra.marketdata.candles.Candle;
import in.arthayantra.marketdata.dataquality.DataQualityController;
import in.arthayantra.marketdata.feed.MarketSurfaceController;
import in.arthayantra.marketdata.feed.NormalizedTick;
import in.arthayantra.marketdata.fundamentals.EquityFundamentals;
import in.arthayantra.marketdata.futures.FuturesTermStructureService;
import in.arthayantra.marketdata.futures.analytics.FuturesBankAnalysisService;
import in.arthayantra.marketdata.futures.analytics.FuturesBankGridService;
import in.arthayantra.marketdata.futures.analytics.FuturesMoversService;
import in.arthayantra.marketdata.futures.analytics.FuturesOiChartService;
import in.arthayantra.marketdata.futures.analytics.FuturesSnapshotReader;
import in.arthayantra.marketdata.futures.analytics.FuturesSpurtService;
import in.arthayantra.marketdata.futures.analytics.OiBuzzService;
import in.arthayantra.marketdata.futures.analytics.OpenHighService;
import in.arthayantra.marketdata.futures.preopen.FuturesPreOpenScan;
import in.arthayantra.marketdata.futures.screener.MarketMoversScreener;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.margin.MarginController;
import in.arthayantra.marketdata.nse.analytics.BreadthService;
import in.arthayantra.marketdata.nse.analytics.EquityBreadthDailyRepository;
import in.arthayantra.marketdata.nse.analytics.EquityDeliveryService;
import in.arthayantra.marketdata.nse.analytics.EquityIndexContributionService;
import in.arthayantra.marketdata.nse.analytics.EquityReturnsService;
import in.arthayantra.marketdata.nse.analytics.EquitySectorService;
import in.arthayantra.marketdata.nse.analytics.FiiDiiController;
import in.arthayantra.marketdata.nse.analytics.NseEodReader;
import in.arthayantra.marketdata.nse.analytics.ParticipantBiasService;
import in.arthayantra.marketdata.nse.preopen.PreOpenEquityScanService;
import in.arthayantra.marketdata.upstox.ExpiredBackfillRepository;
import in.arthayantra.marketdata.upstox.PreOpenIndex;
import in.arthayantra.marketdata.upstox.WorldIndex;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Wire guard for this slice's BigDecimal-as-string fix (57 schemas): each
 * record is constructed DIRECTLY (no MockMvc/DB) and serialized through the SAME
 * {@link ObjectMapper} construction Boot uses in production ({@link
 * ArthaJacksonAutoConfiguration}'s {@code ToStringSerializer} for {@link BigDecimal}).
 *
 * <p>This work is annotation-only ({@code @Schema} never touches runtime serialization), so
 * these tests do not exercise the edit itself; they pin that the WIRE (unchanged) matches
 * what the spec now claims, and are the red-proof target if the serializer registration is
 * ever removed. Every decimal assertion is {@code isTextual()}; {@code asText()} is never
 * used — it accepts a numeric node just as readily and would prove nothing.
 *
 * <p>Dummy values: every {@code BigDecimal} field under test gets a real non-null value;
 * every OTHER field (String/date/list/nested-record/enum/JsonNode) gets {@code null} —
 * valid for any reference-type record component, and irrelevant here since this file only
 * asserts on the decimal components. Primitives get an arbitrary literal (records have no
 * validating compact constructors in this slice, confirmed by reading each source file).
 */
class PlatformBigDecimalWireShapeTest {

  private static final BigDecimal D = new BigDecimal("12.34");

  private ObjectMapper mapper;

  @BeforeEach
  void buildMapperTheWayBootWould() {
    Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
    new ArthaJacksonAutoConfiguration().arthaJacksonCustomizer().customize(builder);
    mapper = builder.build();
  }

  private JsonNode json(Object o) throws Exception {
    return mapper.readTree(mapper.writeValueAsString(o));
  }

  private static void assertTextual(JsonNode owner, String... fields) {
    for (String field : fields) {
      JsonNode n = owner.get(field);
      assertThat(n).as("%s present", field).isNotNull();
      assertThat(n.isTextual()).as("%s must be a JSON string, was %s", field, n).isTrue();
    }
  }

  /** Present-and-null (the {@code types = {"string", "null"}} shape), never an OMITTED key. */
  private static void assertPresentAndNull(JsonNode owner, String... fields) {
    for (String field : fields) {
      assertThat(owner.has(field)).as("%s must be present, not omitted", field).isTrue();
      assertThat(owner.get(field).isNull()).as("%s must serialize as JSON null", field).isTrue();
    }
  }

  @Test
  void aboveMaDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.AboveMa(1, 1, D, 1, 1, D);
    assertTextual(json(instance), "pctAbove20", "pctAbove50");
  }

  @Test
  void advanceDeclineDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.AdvanceDecline(null, 1, 1, 1, 1, D, D, D);
    assertTextual(json(instance), "adRatio", "adRatioPrior", "adRatioDelta");
  }

  @Test
  void bankAnalysisCellDecimalsAreTextual() throws Exception {
    var instance = new FuturesBankAnalysisService.BankAnalysisCell(null, D, D, null);
    assertTextual(json(instance), "ltpPct", "oiPct");
  }

  @Test
  void bankGridRowDecimalsAreTextual() throws Exception {
    var instance = new FuturesBankGridService.BankGridRow(null, null, null, D, D, 1L, 1L, D, null);
    assertTextual(json(instance), "ltp", "pricePct", "oiPct");
  }

  @Test
  void bankRowDecimalsAreTextual() throws Exception {
    var instance = new FuturesMoversService.BankRow(null, null, D, 1L, 1L, D, null);
    assertTextual(json(instance), "ltp", "basis");
  }

  @Test
  void bhavcopyCloseReportDecimalsAreTextual() throws Exception {
    var instance = new BhavcopyCloseCanary.BhavcopyCloseReport(null, null, 1, 1, 1, D, null);
    assertTextual(json(instance), "thresholdPct");
  }

  @Test
  void biasDecimalsAreTextual() throws Exception {
    var instance = new ParticipantBiasService.Bias(null, null, null, D, 1L, 1L, null, 1, null);
    assertTextual(json(instance), "fiiLongPct");
  }

  /**
   * {@code fiiLongPct} is null on the insufficient-history {@code neutral()} shell and whenever the
   * FII index-futures book is flat ({@code totalFut == 0} in {@code classify()}).
   */
  @Test
  void biasFiiLongPctCanBeNull() throws Exception {
    var instance = new ParticipantBiasService.Bias(null, null, null, null, 1L, 1L, null, 1, null);
    assertPresentAndNull(json(instance), "fiiLongPct");
  }

  @Test
  void breadthDayDecimalsAreTextual() throws Exception {
    var instance = new EquityBreadthDailyRepository.BreadthDay(
        null, 1, 1, 1, 1, D, null, null, null, null);
    assertTextual(json(instance), "avgDeliveryPct");
  }

  @Test
  void breadthSummaryDecimalsAreTextual() throws Exception {
    var instance = new BreadthService.BreadthSummary(null, 1, 1, 1, 1, D);
    assertTextual(json(instance), "avgDeliveryPct");
  }

  @Test
  void breadthThrustDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.BreadthThrust(1, D, D, true);
    assertTextual(json(instance), "advRatioMa", "threshold");
  }

  @Test
  void candleDecimalsAreTextual() throws Exception {
    var instance = new Candle(null, null, null, null, D, D, D, D, 1L, null, null);
    assertTextual(json(instance), "open", "high", "low", "close");
  }

  @Test
  void closeMismatchDecimalsAreTextual() throws Exception {
    var instance = new BhavcopyCloseCanary.CloseMismatch(null, D, D, D);
    assertTextual(json(instance), "bhavClose", "kiteClose", "relDiffPct");
  }

  @Test
  void completenessRowDecimalsAreTextual() throws Exception {
    var instance = new DataQualityController.CompletenessRow(
        null, null, 1L, 1L, D, true, null, null);
    assertTextual(json(instance), "coveragePct");
  }

  @Test
  void contractLegDecimalsAreTextual() throws Exception {
    var instance = new FuturesTermStructureService.ContractLeg(null, null, 1L, D, null, null, D, D);
    assertTextual(json(instance), "ltp", "basisAbsolute", "basisAnnualized");
  }

  @Test
  void contribRowDecimalsAreTextual() throws Exception {
    var instance = new EquityIndexContributionService.ContribRow(1, null, D, D, D, D);
    assertTextual(json(instance), "contribution", "changePct", "close", "points");
  }

  @Test
  void deliveryDayDecimalsAreTextual() throws Exception {
    var instance = new EquityDeliveryService.DeliveryDay(null, D, D, D, D, D, D, D, D, null, null);
    assertTextual(
        json(instance), "open", "high", "low", "close", "ltpChangePct", "deliveryPct", "dayRange",
        "dayRangePct");
  }

  @Test
  void deliveryOutlierDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.DeliveryOutlier(null, D, D, D);
    assertTextual(json(instance), "deliveryPct", "mean20", "z");
  }

  @Test
  void deliveryRowDecimalsAreTextual() throws Exception {
    var instance = new BreadthService.DeliveryRow(null, D, D, D);
    assertTextual(json(instance), "deliveryPct", "close", "pctChange");
  }

  @Test
  void diiDivergenceDecimalsAreTextual() throws Exception {
    var instance = new FiiDigestService.DiiDivergence(D, D, true);
    assertTextual(json(instance), "fiiNet", "diiNet");
  }

  @Test
  void dowQuoteDecimalsAreTextual() throws Exception {
    var instance = new MarketSurfaceController.DowQuote(D, D, D, null);
    assertTextual(json(instance), "ltp", "prevClose", "change");
  }

  @Test
  void eodRowDecimalsAreTextual() throws Exception {
    var instance = new FuturesSnapshotReader.EodRow(null, null, D, D, D, D, 1L, 1L, 1L);
    assertTextual(json(instance), "open", "high", "low", "close");
  }

  @Test
  void equityFundamentalsDecimalsAreTextual() throws Exception {
    var instance = new EquityFundamentals(null, null, D, D, D, D, D, D, D, D, D, null);
    assertTextual(
        json(instance), "marketCapCr", "freeFloatMcapCr", "freeFloatPct", "promoterPct", "pe",
        "roe", "netProfitCr", "revenueCr", "revenuePrevCr");
  }

  @Test
  void exportContractDecimalsAreTextual() throws Exception {
    var instance = new ExpiredBackfillRepository.ExportContract(null, null, D, null);
    assertTextual(json(instance), "strike");
  }

  @Test
  void fiiCashDecimalsAreTextual() throws Exception {
    var instance = new FiiDigestService.FiiCash(null, D, 1, null, true);
    assertTextual(json(instance), "net");
  }

  @Test
  void fiiDerivativeAvailabilityDecimalsAreTextual() throws Exception {
    var instance = new FiiDigestService.FiiDerivativeAvailability(true, null, D, null);
    assertTextual(json(instance), "indexFuturesNet");
  }

  @Test
  void fiiDerivativeRowDecimalsAreTextual() throws Exception {
    var instance = new NseEodReader.FiiDerivativeRow(null, null, D, D, D);
    assertTextual(json(instance), "buyValue", "sellValue", "netValue");
  }

  /** {@code buyValue}/{@code sellValue}/{@code netValue} are nullable columns (V024, no NOT NULL). */
  @Test
  void fiiDerivativeRowDecimalsCanBeNull() throws Exception {
    var instance = new NseEodReader.FiiDerivativeRow(null, null, null, null, null);
    assertPresentAndNull(json(instance), "buyValue", "sellValue", "netValue");
  }

  @Test
  void fiiDiiRowDecimalsAreTextual() throws Exception {
    var instance = new NseEodReader.FiiDiiRow(null, null, D, D, D);
    assertTextual(json(instance), "buyValue", "sellValue", "netValue");
  }

  /** {@code buyValue}/{@code sellValue}/{@code netValue} are nullable columns (V012, no NOT NULL). */
  @Test
  void fiiDiiRowDecimalsCanBeNull() throws Exception {
    var instance = new NseEodReader.FiiDiiRow(null, null, null, null, null);
    assertPresentAndNull(json(instance), "buyValue", "sellValue", "netValue");
  }

  @Test
  void fiiFuturesLongShortDecimalsAreTextual() throws Exception {
    var instance = new FiiDigestService.FiiFuturesLongShort(null, 1L, 1L, D, D, null, 1, true);
    assertTextual(json(instance), "ratio", "ratioDelta");
  }

  @Test
  void futOiCandleDecimalsAreTextual() throws Exception {
    var instance = new FuturesOiChartService.FutOiCandle(null, D, D, D, D, 1L, null);
    assertTextual(json(instance), "open", "high", "low", "close");
  }

  @Test
  void futPointDecimalsAreTextual() throws Exception {
    var instance = new FuturesSnapshotReader.FutPoint(
        null, null, null, D, null, null, D, D, D, D, null, null);
    assertTextual(json(instance), "ltp", "dayOpen", "dayHigh", "dayLow", "prevClose");
  }

  @Test
  void futSpurtDecimalsAreTextual() throws Exception {
    var instance = new FuturesSpurtService.FutSpurt(null, D, D, D, 1L, 1L, D, null);
    assertTextual(json(instance), "ltp", "prevClose", "pricePct", "spurtPct");
  }

  @Test
  void globalCueDecimalsAreTextual() throws Exception {
    var instance = new DayContextService.GlobalCue(null, D, D);
    assertTextual(json(instance), "ltp", "changePct");
  }

  @Test
  void indexConcentrationDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.IndexConcentration(null, D, 1, D);
    assertTextual(json(instance), "indexChangePct", "topShare");
  }

  @Test
  void indexContributionDecimalsAreTextual() throws Exception {
    var instance = new EquityIndexContributionService.IndexContribution(
        null, D, D, D, D, D, D, null, null, null, true);
    assertTextual(
        json(instance), "indexChangePct", "advanceTotal", "declineTotal", "indexLevel",
        "advancePoints", "declinePoints");
  }

  @Test
  void indexPriceActionDecimalsAreTextual() throws Exception {
    var instance = new DayContextService.IndexPriceAction(null, D, D, D, D, null, null, null);
    assertTextual(json(instance), "gapOpenPct", "dayRange", "avgRange20", "rangeVsAvg");
  }

  @Test
  void instrumentDecimalsAreTextual() throws Exception {
    var instance = new Instrument(
        null, null, null, null, null, null, null, null, null, D, D, null, true);
    assertTextual(json(instance), "strike", "tickSize");
  }

  /**
   * {@code GET /api/v1/instruments/{underlying}/strikes} returns a BARE {@code List<BigDecimal>} —
   * no record component to hang {@code @Schema} on, so the sweep's annotation-based method could
   * not reach it and the spec kept claiming {@code items: {type: number}} while the wire carried
   * strings (generated TS read {@code number[]}). The element type is now declared via
   * {@code @ArraySchema} on the handler; this pins the WIRE side of that claim.
   */
  @Test
  void strikesArrayElementsAreTextual() throws Exception {
    JsonNode array = json(java.util.List.of(new BigDecimal("18000"), new BigDecimal("18100.50")));
    assertThat(array.isArray()).as("strikes is a bare JSON array").isTrue();
    assertThat(array).hasSize(2);
    for (JsonNode element : array) {
      assertThat(element.isTextual())
          .as("every strike must be a JSON string, was %s", element)
          .isTrue();
    }
  }

  @Test
  void longShortRowDecimalsAreTextual() throws Exception {
    var instance = new FiiDiiController.LongShortRow(null, 1L, 1L, D);
    assertTextual(json(instance), "ratio");
  }

  /** {@code ratio} is null when {@code fiiShort} is zero (`longShort()`'s division guard). */
  @Test
  void longShortRowRatioCanBeNull() throws Exception {
    var instance = new FiiDiiController.LongShortRow(null, 1L, 0L, null);
    assertPresentAndNull(json(instance), "ratio");
  }

  @Test
  void marginResponseDecimalsAreTextual() throws Exception {
    var instance = new MarginController.MarginResponse(true, null, D, D, D, D, D, D, D, D);
    assertTextual(
        json(instance), "spanMargin", "exposureMargin", "equityMargin", "netBuyPremium",
        "additionalMargin", "totalMargin", "requiredMargin", "finalMargin");
  }

  @Test
  void moverRowDecimalsAreTextual() throws Exception {
    var instance = new FuturesMoversService.MoverRow(null, D, D, D, D, D, D, null);
    assertTextual(json(instance), "ltp", "pricePct", "oiPct", "dayOpen", "dayHigh", "dayLow");
  }

  @Test
  void normalizedTickDecimalsAreTextual() throws Exception {
    var instance = new NormalizedTick(null, null, D, 1L, null, null, 1L);
    assertTextual(json(instance), "lastPrice");
  }

  @Test
  void preOpenIndexDecimalsAreTextual() throws Exception {
    var instance = new PreOpenIndex(null, null, D, D, D, D, null);
    assertTextual(json(instance), "ltp", "prevClose", "netChange", "changePct");
  }

  @Test
  void preOpenRowDecimalsAreTextual() throws Exception {
    var instance = new FuturesPreOpenScan.PreOpenRow(null, D, D, D, D, null);
    assertTextual(json(instance), "preOpenPrice", "prevClose", "change", "changePct");
  }

  @Test
  void returnLeaderDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.ReturnLeader(null, null, D);
    assertTextual(json(instance), "returnPct");
  }

  @Test
  void returnsRowDecimalsAreTextual() throws Exception {
    var instance = new EquityReturnsService.ReturnsRow(null, null, D, D, D, D, D, D);
    assertTextual(json(instance), "ltp", "r1d", "r1w", "r1m", "r6m", "r1y");
  }

  @Test
  void scanRowDecimalsAreTextual() throws Exception {
    var instance = new PreOpenEquityScanService.ScanRow(null, D, D, D, D, null);
    assertTextual(json(instance), "preOpenPrice", "prevClose", "change", "changePct");
  }

  @Test
  void screenerRowDecimalsAreTextual() throws Exception {
    var instance = new MarketMoversScreener.ScreenerRow(
        null, D, D, D, null, 1, true, true, D, 1L, true, true);
    assertTextual(json(instance), "ltp", "pricePct", "oiPct", "dailyRsi");
  }

  @Test
  void sectorAggDecimalsAreTextual() throws Exception {
    var instance = new EquitySectorService.SectorAgg(null, D, 1, 1, 1);
    assertTextual(json(instance), "avgChangePct");
  }

  @Test
  void sectorRotationDecimalsAreTextual() throws Exception {
    var instance = new EquityDigestService.SectorRotation(null, D, 1, 1, null, null);
    assertTextual(json(instance), "avgChangePct");
  }

  @Test
  void setupDecimalsAreTextual() throws Exception {
    var instance = new OpenHighService.Setup(null, D, D, D, D, D);
    assertTextual(json(instance), "dayOpen", "dayHigh", "dayLow", "ltp", "farPct");
  }

  @Test
  void stockChangeDecimalsAreTextual() throws Exception {
    var instance = new EquitySectorService.StockChange(null, null, D, D, D);
    assertTextual(json(instance), "changePct", "close", "prevClose");
  }

  @Test
  void termStructureDecimalsAreTextual() throws Exception {
    var instance = new FuturesTermStructureService.TermStructure(
        null, D, null, D, true, null, null, null);
    assertTextual(json(instance), "spot", "calendarSpread");
  }

  @Test
  void termStructureStateDecimalsAreTextual() throws Exception {
    var instance = new FuturesDigestService.TermStructureState(null, D, null, D, D, true, null);
    assertTextual(json(instance), "spot", "calendarSpread", "nearBasis");
  }

  @Test
  void tileDecimalsAreTextual() throws Exception {
    var instance = new OiBuzzService.Tile(null, D, D, D, D, D, null, null, null);
    assertTextual(json(instance), "changePct", "ltp", "open", "high", "low");
  }

  /**
   * {@code changePct} is null when the quote has no usable prev-close (the {@code
   * prevClose.signum() > 0} guard in {@code live()}); {@code open}/{@code high}/{@code low} are
   * null whenever the quote's OHLC block itself is absent. Only {@code ltp} stays non-null (the
   * caller skips a pin entirely when the quote has no LTP).
   */
  @Test
  void tileDecimalsCanBeNull() throws Exception {
    var instance = new OiBuzzService.Tile(null, null, D, null, null, null, null, null, null);
    assertPresentAndNull(json(instance), "changePct", "open", "high", "low");
  }

  @Test
  void underlyingQuadrantDecimalsAreTextual() throws Exception {
    var instance = new FuturesDigestService.UnderlyingQuadrant(
        null, null, null, D, D, 1L, 1L, D, null);
    assertTextual(json(instance), "ltp", "pricePct", "oiPct");
  }

  @Test
  void vixDecimalsAreTextual() throws Exception {
    var instance = new DayContextService.Vix(D, D, D, null, null);
    assertTextual(json(instance), "level", "change", "changePct");
  }

  @Test
  void vixQuoteDecimalsAreTextual() throws Exception {
    var instance = new MarketSurfaceController.VixQuote(D, D, D, D, D, D, D, null);
    assertTextual(
        json(instance), "ltp", "dayHigh", "dayLow", "dayOpen", "prevClose", "change", "changePct");
  }

  @Test
  void worldIndexDecimalsAreTextual() throws Exception {
    var instance = new WorldIndex(null, null, null, null, D, D, D, D, D, D, D, null, null);
    assertTextual(
        json(instance), "ltp", "prevClose", "netChange", "changePct", "open", "high", "low");
  }

}
