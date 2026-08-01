package in.arthayantra.marketdata.screener;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.jackson.ArthaJacksonAutoConfiguration;
import in.arthayantra.marketdata.screener.manas.ManasAroraBacktestService;
import in.arthayantra.marketdata.screener.manas.ManasController;
import in.arthayantra.marketdata.screener.manas.ManasFunnelService;
import in.arthayantra.marketdata.screener.minervini.MinerviniBacktestService;
import in.arthayantra.marketdata.screener.minervini.MinerviniController;
import in.arthayantra.marketdata.screener.minervini.MinerviniFunnelService;
import in.arthayantra.marketdata.screener.minervini.MinerviniHitRateService;
import in.arthayantra.marketdata.screener.minervini.RegimeService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Wire guard for this slice's BigDecimal-as-string fix (24 schemas): each
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
class ScreenerBigDecimalWireShapeTest {

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
  void deepSwingRunResultDecimalsAreTextual() throws Exception {
    var instance = new DeepSwingRunResult(
        null, null, null, null, 1, D, D, D, D, D, 1, 1, D, D, null, null);
    assertTextual(
        json(instance), "capital", "totalReturnPct", "cagrPct", "maxDrawdownPct", "sharpe",
        "winRatePct", "profitFactor");
  }

  @Test
  void deepSwingTradeDecimalsAreTextual() throws Exception {
    var instance = new DeepSwingTrade(null, null, null, D, null, D, D, 1, null, D);
    assertTextual(json(instance), "entryPrice", "exitPrice", "pnlPct", "rsRankAtEntry");
  }

  @Test
  void diffRowDecimalsAreTextual() throws Exception {
    var instance = new ScreenerHistory.DiffRow(null, D);
    assertTextual(json(instance), "rsRank");
  }

  @Test
  void geometryDecimalsAreTextual() throws Exception {
    var instance = new MinerviniController.Geometry(
        true, null, D, D, D, null, null, null, true, true, null, D, true, null);
    assertTextual(json(instance), "pivot", "deepestPct", "tightestPct", "cheatPivot");
  }

  @Test
  void horizonStatDecimalsAreTextual() throws Exception {
    var instance = new MinerviniHitRateService.HorizonStat(1, 1, D, D, D, D, D, D);
    assertTextual(
        json(instance), "winRatePct", "beatBenchmarkRatePct", "meanReturnPct",
        "meanBenchmarkReturnPct", "meanExcessReturnPct", "medianReturnPct");
  }

  @Test
  void manasCandidateAnalysisDecimalsAreTextual() throws Exception {
    var instance = new ManasController.CandidateAnalysis(
        null, null, null, true, D, D, D, D, D, D, D, D, D, D, true, true, true, true, true, D, D,
        null, 1, true, null);
    assertTextual(
        json(instance), "close", "sma50", "sma200", "high52w", "low52w", "withinHighPct",
        "aboveLowPct", "avgVol20", "avgVol50", "turnover50", "freeFloatMcapCr", "freeFloatPct");
  }

  @Test
  void manasFunnelRowDecimalsAreTextual() throws Exception {
    var instance = new ManasFunnelService.FunnelRow(null, D, D, null, D, null, D, D, D, D);
    assertTextual(
        json(instance), "close", "aboveLowPct", "pivot", "pctToPivot", "breakoutPivot", "vcpPivot",
        "rsRank");
  }

  @Test
  void manasPortfolioStatDecimalsAreTextual() throws Exception {
    var instance = new ManasAroraBacktestService.PortfolioStat(
        1, D, D, D, D, 1, 1, D, 1, D, D, D, D, null);
    assertTextual(
        json(instance), "totalReturnPct", "cagrPct", "maxDrawdownPct", "sharpe", "avgExposurePct",
        "positiveMonthsPct", "bestMonthPct", "worstMonthPct", "avgMonthPct");
  }

  @Test
  void manasRowDecimalsAreTextual() throws Exception {
    var instance = new ManasController.Row(
        null, null, D, D, D, D, D, D, D, D, D, D, true, true, true, true, true, D, D, null, 1, true,
        D);
    assertTextual(
        json(instance), "close", "sma50", "sma200", "high52w", "low52w", "avgVol20", "avgVol50",
        "turnover50", "withinHighPct", "aboveLowPct", "freeFloatMcapCr", "freeFloatPct", "rsRank");
  }

  @Test
  void manasSetupStatDecimalsAreTextual() throws Exception {
    var instance = new ManasAroraBacktestService.SetupStat(
        null, 1, 1, 1, D, D, D, D, D, D, D, D, D, 1, 1, 1, 1, D);
    assertTextual(
        json(instance), "winRatePct", "avgWinPct", "avgLossPct", "payoffRatio", "expectancyPct",
        "profitFactor", "avgBarsHeld", "bestTradePct", "worstTradePct", "stopOutPct");
  }

  @Test
  void manasSlotCellDecimalsAreTextual() throws Exception {
    var instance = new ManasAroraBacktestService.SlotCell(1, 1, 1, D, D, D, D);
    assertTextual(json(instance), "grossCagrPct", "netCagrPct", "netDrawdownPct", "netSharpe");
  }

  @Test
  void manasYearReturnDecimalsAreTextual() throws Exception {
    var instance = new ManasAroraBacktestService.YearReturn(1, D, 1);
    assertTextual(json(instance), "returnPct");
  }

  @Test
  void minerviniCandidateAnalysisDecimalsAreTextual() throws Exception {
    var instance = new MinerviniController.CandidateAnalysis(
        null, null, null, true, D, D, D, D, D, D, D, D, D, D, D, D, null, 1, true, null, null);
    assertTextual(
        json(instance), "close", "sma50", "sma150", "sma200", "high52w", "low52w", "pctFromHigh",
        "pctAboveLow", "rsRank", "avgTurnover50", "freeFloatMcapCr", "freeFloatPct");
  }

  @Test
  void minerviniFunnelRowDecimalsAreTextual() throws Exception {
    var instance = new MinerviniFunnelService.FunnelRow(
        null, D, D, null, true, D, D, true, null, D);
    assertTextual(json(instance), "close", "rsRank", "pivot", "cheatPivot", "pctToPivot");
  }

  @Test
  void minerviniRowDecimalsAreTextual() throws Exception {
    var instance = new MinerviniController.Row(
        null, null, D, D, D, D, D, D, D, D, D, D, D, D, null, 1, true, null);
    assertTextual(
        json(instance), "close", "sma50", "sma150", "sma200", "high52w", "low52w", "pctFromHigh",
        "pctAboveLow", "rsRank", "avgTurnover50", "freeFloatMcapCr", "freeFloatPct");
  }

  @Test
  void portfolioStatDecimalsAreTextual() throws Exception {
    var instance = new MinerviniBacktestService.PortfolioStat(
        1, D, D, D, D, 1, 1, D, 1, D, D, D, D, null);
    assertTextual(
        json(instance), "totalReturnPct", "cagrPct", "maxDrawdownPct", "sharpe", "avgExposurePct",
        "positiveMonthsPct", "bestMonthPct", "worstMonthPct", "avgMonthPct");
  }

  @Test
  void regimeDecimalsAreTextual() throws Exception {
    var instance = new RegimeService.Regime(null, D, 1);
    assertTextual(json(instance), "advanceRatio");
  }

  @Test
  void rotationResultDecimalsAreTextual() throws Exception {
    var instance = new MinerviniBacktestService.RotationResult(1, D, 1, null);
    assertTextual(json(instance), "marginPct");
  }

  @Test
  void rowDecimalsAreTextual() throws Exception {
    var instance = new ScreenerService.Row(null, null, D, D, D, D, D, null);
    assertTextual(
        json(instance), "latestClose", "pastClose", "value", "avgVolume", "distanceFromHigh52w");
  }

  @Test
  void setupStatDecimalsAreTextual() throws Exception {
    var instance = new MinerviniBacktestService.SetupStat(
        null, 1, 1, 1, D, D, D, D, D, D, D, D, D, 1, 1, 1, 1, D);
    assertTextual(
        json(instance), "winRatePct", "avgWinPct", "avgLossPct", "payoffRatio", "expectancyPct",
        "profitFactor", "avgBarsHeld", "bestTradePct", "worstTradePct", "stopOutPct");
  }

  @Test
  void setupViewDecimalsAreTextual() throws Exception {
    var instance = new ManasController.SetupView(null, true, null, D, null, null);
    assertTextual(json(instance), "pivot");
  }

  @Test
  void slotCellDecimalsAreTextual() throws Exception {
    var instance = new MinerviniBacktestService.SlotCell(1, 1, 1, D, D, D, D);
    assertTextual(json(instance), "grossCagrPct", "netCagrPct", "netDrawdownPct", "netSharpe");
  }

  @Test
  void sweepCellDecimalsAreTextual() throws Exception {
    var instance = new MinerviniBacktestService.SweepCell(1L, 1L, 1, D, D, D);
    assertTextual(json(instance), "netCagrPct", "netDrawdownPct", "netSharpe");
  }

  @Test
  void yearReturnDecimalsAreTextual() throws Exception {
    var instance = new MinerviniBacktestService.YearReturn(1, D, 1);
    assertTextual(json(instance), "returnPct");
  }

}
