package in.arthayantra.marketdata.options.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.jackson.ArthaJacksonAutoConfiguration;
import in.arthayantra.marketdata.context.ExpiryCompareService;
import in.arthayantra.marketdata.options.IvAnalyticsService;
import in.arthayantra.marketdata.options.OptionsChainService;
import in.arthayantra.marketdata.options.OptionsDigestService;
import in.arthayantra.marketdata.options.OptionsSnapshotRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Wire guard for this slice's BigDecimal-as-string fix (37 schemas): each
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
class OptionsBigDecimalWireShapeTest {

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

  @Test
  void activeStrikeIvPointDecimalsAreTextual() throws Exception {
    var instance = new ActiveStrikeService.ActiveStrikeIvPoint(null, D, D, D);
    assertTextual(json(instance), "ceIv", "peIv", "price");
  }

  @Test
  void activeStrikesResponseDecimalsAreTextual() throws Exception {
    var instance = new OptionsAnalyticsController.ActiveStrikesResponse(
        D, D, null, null, null, null, null, null, null);
    assertTextual(json(instance), "sentimentPct", "sentimentLevelPct");
  }

  @Test
  void alignedPointDecimalsAreTextual() throws Exception {
    var instance = new ExpiryCompareService.AlignedPoint(null, null, null, D, D, D, D);
    assertTextual(json(instance), "aPcr", "bPcr", "aSpot", "bSpot");
  }

  @Test
  void atmIvDecimalsAreTextual() throws Exception {
    var instance = new OptionsDigestService.AtmIv(D, D, null, 1, true);
    assertTextual(json(instance), "iv", "rank");
  }

  @Test
  void bigOiRowDecimalsAreTextual() throws Exception {
    var instance = new OiBigOiService.BigOiRow(D, null, 1L, 1L, D);
    assertTextual(json(instance), "strike", "ltp");
  }

  @Test
  void calendarSpreadChartDecimalsAreTextual() throws Exception {
    var instance = new CalendarSpreadChartService.CalendarSpreadChart(
        null, D, null, null, null, null, D, D, null, null);
    assertTextual(json(instance), "strike", "underlyingLtp", "underlyingDayOpen");
  }

  @Test
  void chainDecimalsAreTextual() throws Exception {
    var instance = new OptionsChainService.Chain(
        null, null, D, D, null, D, D, true, true, null, null);
    assertTextual(json(instance), "spot", "forward", "riskFreeRate", "pcr");
  }

  @Test
  void chainTableDecimalsAreTextual() throws Exception {
    var instance = new OptionsAnalyticsController.ChainTable(
        null, null, D, D, null, D, D, true, true, null, null, null, null);
    assertTextual(json(instance), "spot", "forward", "riskFreeRate", "pcr");
  }

  @Test
  void chainTableRowDecimalsAreTextual() throws Exception {
    var instance = new OptionsAnalyticsController.ChainTableRow(D, null, null);
    assertTextual(json(instance), "strike");
  }

  @Test
  void expiryCompareTrendPointDecimalsAreTextual() throws Exception {
    var instance = new ExpiryCompareService.TrendPoint(null, 1L, 1L, 1L, D, D, D, D);
    assertTextual(json(instance), "pcr", "spot", "ceLtp", "peLtp");
  }

  @Test
  void historyPointDecimalsAreTextual() throws Exception {
    var instance = new IvAnalyticsService.HistoryPoint(null, D, D, D, D);
    assertTextual(json(instance), "iv", "atmIv", "iv30d", "spot");
  }

  @Test
  void ivHistoryDecimalsAreTextual() throws Exception {
    var instance = new IvAnalyticsService.IvHistory(null, null, D, D, null, 1, 1, true);
    assertTextual(json(instance), "currentIv", "rank");
  }

  @Test
  void legDecimalsAreTextual() throws Exception {
    var instance = new OptionsChainService.Leg(
        null, null, D, D, D, null, null, null, D, D, D, D, D, D, D, D, D, D, D, D, null, null);
    assertTextual(
        json(instance), "ltp", "bid", "ask", "iv", "delta", "gamma", "theta", "vega", "rho",
        "vanna", "charm", "vomma", "speed", "zomma", "color");
  }

  @Test
  void legDeltasDecimalsAreTextual() throws Exception {
    var instance = new OptionsAnalyticsController.LegDeltas(null, D, D, D, null);
    assertTextual(json(instance), "oiChangePct", "ltpChange", "ltpChangePct");
  }

  @Test
  void logEventDecimalsAreTextual() throws Exception {
    var instance = new BigOiLogService.LogEvent(null, D, D, null, D, D, null, 1L, null);
    assertTextual(json(instance), "spot", "strike", "ltp", "ltpChange");
  }

  @Test
  void maxPainDecimalsAreTextual() throws Exception {
    var instance = new OptionsDigestService.MaxPain(D, D, D);
    assertTextual(json(instance), "now", "atOpen", "drift");
  }

  @Test
  void oiStatsDecimalsAreTextual() throws Exception {
    var instance = new OptionsAnalyticsController.OiStats(D, D, 1L, 1L, null, null);
    assertTextual(json(instance), "pcr", "maxPain");
  }

  @Test
  void oiStructureDecimalsAreTextual() throws Exception {
    var instance = new OptionsDigestService.OiStructure(null, D, 1L);
    assertTextual(json(instance), "spotDelta");
  }

  @Test
  void oiTrendPointDecimalsAreTextual() throws Exception {
    var instance = new OiTrendingService.TrendPoint(null, 1L, 1L, 1L, D, null, D, D);
    assertTextual(json(instance), "spot", "ceLtp", "peLtp");
  }

  @Test
  void pcrDecimalsAreTextual() throws Exception {
    var instance = new OptionsDigestService.Pcr(D, D, D, D, D);
    assertTextual(json(instance), "now", "atOpen", "priorEod", "deltaVsOpen", "deltaVsPriorEod");
  }

  @Test
  void pcrSeriesPointDecimalsAreTextual() throws Exception {
    var instance = new PcrSeriesPoint(null, D, D);
    assertTextual(json(instance), "pcr", "spot");
  }

  @Test
  void premiumChainDecimalsAreTextual() throws Exception {
    var instance = new OiPremiumService.PremiumChain(null, D, D, D, null);
    assertTextual(json(instance), "atmStrike", "atmStraddle", "spot");
  }

  @Test
  void premiumRowDecimalsAreTextual() throws Exception {
    var instance = new OiPremiumService.PremiumRow(D, D, D, D);
    assertTextual(json(instance), "strike", "straddle", "ce", "pe");
  }

  @Test
  void premiumSeriesPointDecimalsAreTextual() throws Exception {
    var instance = new OiPremiumService.PremiumSeriesPoint(null, D, D, D);
    assertTextual(json(instance), "atmStrike", "atmStraddle", "spot");
  }

  @Test
  void sentimentPointDecimalsAreTextual() throws Exception {
    var instance = new ActiveStrikeService.SentimentPoint(null, D, D);
    assertTextual(json(instance), "sentimentPct", "levelPct");
  }

  @Test
  void snapshotRowDecimalsAreTextual() throws Exception {
    var instance = new OptionsSnapshotRepository.SnapshotRow(
        null, null, null, D, null, null, D, D, D, null, null, null, D, D, D, D, D, D, D, null, null,
        D, D);
    assertTextual(
        json(instance), "strike", "ltp", "bid", "ask", "spotPrice", "iv", "delta", "gamma", "theta",
        "vega", "rho", "forwardPrice", "riskFreeRate");
  }

  @Test
  void spreadCandleDecimalsAreTextual() throws Exception {
    var instance = new CalendarSpreadChartService.SpreadCandle(null, D, D, D, D, D, D, 1L);
    assertTextual(json(instance), "open", "high", "low", "close", "nearClose", "farClose");
  }

  @Test
  void spurtSummaryDecimalsAreTextual() throws Exception {
    var instance = new OiSpurtService.SpurtSummary(null, D, 1L, D, D);
    assertTextual(json(instance), "spotDelta", "oiChangePct", "priceChangePct");
  }

  @Test
  void straddleDecimalsAreTextual() throws Exception {
    var instance = new OptionsDigestService.Straddle(D, D, D, D);
    assertTextual(json(instance), "atmStrike", "now", "atOpen", "deltaPct");
  }

  @Test
  void straddleCandleDecimalsAreTextual() throws Exception {
    var instance = new StraddleChartService.StraddleCandle(null, D, D, D, D, D, D, 1L);
    assertTextual(json(instance), "open", "high", "low", "close", "ceClose", "peClose");
  }

  @Test
  void straddleChartDecimalsAreTextual() throws Exception {
    var instance = new StraddleChartService.StraddleChart(
        null, null, D, D, null, D, D, null, D, D, D, null);
    assertTextual(
        json(instance), "callStrike", "putStrike", "underlyingLtp", "underlyingDayOpen",
        "combinedVwap", "slBufferPoints", "slLevel");
  }

  @Test
  void strikeRowDecimalsAreTextual() throws Exception {
    var instance = new OptionsChainService.StrikeRow(D, null, null);
    assertTextual(json(instance), "strike");
  }

  @Test
  void strikeSessionStatDecimalsAreTextual() throws Exception {
    var instance = new OpenHighStatsService.StrikeSessionStat(
        D, null, D, D, D, D, null, null, D, true, true, D, D, D);
    assertTextual(
        json(instance), "strike", "open", "high", "low", "last", "prevClose", "fallPctFromOpen",
        "fallPctFromPrevClose", "oiChangePct");
  }

  @Test
  void strikeSessionStatsDecimalsAreTextual() throws Exception {
    var instance = new OpenHighStatsService.StrikeSessionStats(null, null, null, D, D, 1, null);
    assertTextual(json(instance), "spot", "atmStrike");
  }

  @Test
  void strikeSpurtDecimalsAreTextual() throws Exception {
    var instance = new OiSpurtService.StrikeSpurt(D, null, D, D, null, 1L, D, D, D, null, null);
    assertTextual(
        json(instance), "strike", "ltp", "prevLtp", "spurtPct", "ltpChange", "ltpChangePct");
  }

  @Test
  void strikeViewDecimalsAreTextual() throws Exception {
    var instance = new OptionsAnalyticsController.StrikeView(D, 1L, 1L);
    assertTextual(json(instance), "strike");
  }

  @Test
  void underlyingDivergenceDecimalsAreTextual() throws Exception {
    var instance = new CrossSourceOiCanary.UnderlyingDivergence(null, null, null, null, 1, D, D);
    assertTextual(json(instance), "meanDivergencePct", "maxDivergencePct");
  }

}
