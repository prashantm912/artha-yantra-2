package in.arthayantra.strategysignal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.jackson.ArthaJacksonAutoConfiguration;
import in.arthayantra.strategysignal.execution.OrderGateway;
import in.arthayantra.strategysignal.insights.Insight;
import in.arthayantra.strategysignal.insights.RejectionReader;
import in.arthayantra.strategysignal.insights.SignalCompareReader;
import in.arthayantra.strategysignal.insights.StrategyEvidenceReader;
import in.arthayantra.strategysignal.paper.GraduationController;
import in.arthayantra.strategysignal.paper.GraduationService;
import in.arthayantra.strategysignal.paper.PaperAccountService;
import in.arthayantra.strategysignal.paper.PaperController;
import in.arthayantra.strategysignal.paper.PaperMarginController;
import in.arthayantra.strategysignal.paper.PaperService;
import in.arthayantra.strategysignal.paper.PaperViews;
import in.arthayantra.strategysignal.signals.DataHealthFlags;
import in.arthayantra.strategysignal.signals.ShadowPositionRepository;
import in.arthayantra.strategysignal.signals.ShadowVariantsController;
import in.arthayantra.strategysignal.signals.SignalRejectionRepository;
import in.arthayantra.strategysignal.signals.SignalViews;
import in.arthayantra.strategysignal.swing.SellDecisionRepository;
import in.arthayantra.strategysignal.swing.SwingSellDecisionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Pins the WIRE FORM of every {@code BigDecimal} on a strategy-signal RESPONSE record, so the
 * committed OpenAPI spec's {@code type: string} is a measured claim rather than an annotation
 * nobody checked (CLAUDE.md, #1195; the backtest / market-data precedents are #1197 / #1190).
 *
 * <p>Why this test has to exist: {@code ArthaJacksonAutoConfiguration} routes every {@code
 * BigDecimal} through {@code ToStringSerializer} platform-wide, so these fields are decimal
 * STRINGS on the wire — but springdoc infers {@code number} from the Java type, and {@code
 * @Schema(types = ...)} UNIONS with that inference instead of replacing it. Only {@code type =
 * "string"} replaces it. A test that read values with {@code asText()} would pass against a
 * number node too and prove nothing; every decimal assertion here is {@code isTextual()}.
 *
 * <p>The mapper is built the way Boot builds it (common-web-core's {@code
 * ArthaJacksonConventionsTest} is the same instrument), so deleting the {@code
 * serializerByType(BigDecimal.class, ...)} line turns every decimal assertion RED while the
 * integral-count / string / boolean assertions stay GREEN — the contrast that shows they
 * discriminate.
 *
 * <p>Each of the ~29 records exercised here is built via a small private static factory method
 * (one per record, one {@code new Xxx(...)} call site each) rather than positionally inline —
 * a widened record then needs exactly one edit, in its own factory, instead of touching every
 * test method that uses it. This closes the collision class that broke #1203/#1193: a record
 * widened on one branch made this file's inline positional constructions fail to compile on
 * rebase, invisibly to either branch's own green CI run (task_56865a7d).
 */
class BigDecimalWireContractTest {

  private ObjectMapper mapper;

  @BeforeEach
  void buildMapperTheWayBootWould() {
    Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
    new ArthaJacksonAutoConfiguration().arthaJacksonCustomizer().customize(builder);
    mapper = builder.build();
  }

  private JsonNode wire(Object value) {
    return mapper.valueToTree(value);
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  /**
   * A realistic {@code data_health} node for a SCORED rejection row (V054). Built from the real
   * {@link DataHealthFlags} record rather than hand-rolled JSON, so this fixture cannot drift from
   * the shape production writes.
   *
   * <p>Not {@code null}: the row below is a {@code confluence-blocked} block that reached the
   * composite stage, so it is context-bearing and production always computes a verdict for it. The
   * flags are the two that live rows actually carry today — {@code ivRank} and {@code dowUp} were
   * absent on 100% of 3,700 measured context-bearing rows (IV-history floor unmet; Dow feed un-armed
   * by design), which is why {@code degraded} is true. Contributes no decimals, so it neither helps
   * nor weakens this class's assertions — it is here to keep the fixture faithful.
   */
  private JsonNode dataHealth() {
    return mapper.valueToTree(
        new DataHealthFlags(true, true, false, List.of("iv-rank-absent", "dow-absent")));
  }

  // ---- paper/PaperService -------------------------------------------------------------------

  /**
   * ⚠️ {@code closedAt} is deliberately NOT a parameter. #1222 established that a {@code TradeDto}
   * can never carry a null one — {@code PaperPositionRepository.close()} is the sole writer of
   * {@code status='CLOSED'} and stamps {@code closed_at} in the same atomic UPDATE, {@code trades()}
   * reads only {@code listClosed}, and V055 now pins the invariant in the database. A parameter here
   * would let a caller reconstruct a state production cannot emit, which is exactly the fixture
   * unfaithfulness this file exists to avoid.
   */
  private static PaperService.TradeDto tradeDto() {
    return new PaperService.TradeDto(
        1L, "NFO", "NIFTY26JUL24500CE", "BUY", 50L, bd("101.5"), bd("245.0"), OffsetDateTime.now(),
        OffsetDateTime.now());
  }

  private static PaperService.FeeBreakdown feeBreakdown() {
    return new PaperService.FeeBreakdown(
        bd("10.5"), bd("1.2"), bd("0.3"), bd("2.1"), bd("0.05"), bd("0.02"), bd("14.17"));
  }

  private static PaperService.PositionDto positionDto(
      BigDecimal markPrice, BigDecimal unrealizedPnl, BigDecimal stopLoss, BigDecimal takeProfit) {
    return new PaperService.PositionDto(
        1L, "NFO", "NIFTY26JUL24500CE", "BUY", 50L, bd("101.5"), markPrice, unrealizedPnl, bd("0"),
        "OPEN", OffsetDateTime.now(), stopLoss, takeProfit, null);
  }

  private static PaperService.OpeningSignal openingSignal() {
    return new PaperService.OpeningSignal(
        1L, "FIRED", "BUY", bd("101.5"), bd("95.0"), bd("120.0"), bd("0.82"), OffsetDateTime.now(),
        null, null, null);
  }

  private static PaperService.OrderLeg orderLeg() {
    return new PaperService.OrderLeg(
        1L, 2L, "BUY", 50L, "FILLED", OffsetDateTime.now(), OffsetDateTime.now(), bd("101.5"),
        "SIMULATED", bd("0.05"), feeBreakdown());
  }

  private static PaperService.PositionDetail positionDetail() {
    return new PaperService.PositionDetail(
        1L, "manas-1", "NFO", "NIFTY26JUL24500CE", "BUY", 50L, bd("101.5"), bd("110.0"),
        bd("475.0"), bd("0"), "OPEN", OffsetDateTime.now(), null, null, bd("95.0"), bd("120.0"), 1L,
        bd("50000"), bd("12.5"), 0, 9L, null, List.of());
  }

  @Test
  void tradeDtoDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(tradeDto());
    assertThat(node.get("avgEntryPrice").isTextual()).as("avgEntryPrice").isTrue();
    assertThat(node.get("realizedPnl").isTextual()).as("realizedPnl").isTrue();
  }

  @Test
  void feeBreakdownDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(feeBreakdown());
    for (String field :
        List.of("brokerage", "stt", "exchangeTxn", "gst", "stamp", "sebi", "total")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void positionDtoDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(positionDto(bd("110.0"), bd("475.0"), bd("95.0"), bd("120.0")));
    for (String field :
        List.of("avgEntryPrice", "markPrice", "unrealizedPnl", "realizedPnl", "stopLoss", "takeProfit")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void positionDtoNullableDecimalsStayPresentAndNull() {
    JsonNode node = wire(positionDto(null, null, null, null));
    for (String field : List.of("markPrice", "unrealizedPnl", "stopLoss", "takeProfit")) {
      assertThat(node.has(field)).as(field + " present").isTrue();
      assertThat(node.get(field).isNull()).as(field + " null").isTrue();
    }
  }

  @Test
  void openingSignalDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(openingSignal());
    for (String field : List.of("entryPrice", "stopLoss", "target", "compositeScore")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void orderLegDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(orderLeg());
    assertThat(node.get("fillPrice").isTextual()).as("fillPrice").isTrue();
    assertThat(node.get("slippageApplied").isTextual()).as("slippageApplied").isTrue();
  }

  @Test
  void positionDetailDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(positionDetail());
    for (String field :
        List.of(
            "avgEntryPrice", "markPrice", "unrealizedPnl", "realizedPnl", "stopLoss", "takeProfit",
            "marginSnapshot", "marginPct")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  // ---- paper/GraduationController + GraduationService (name-collision twins) ----------------

  private static GraduationController.Thresholds graduationControllerThresholds() {
    return new GraduationController.Thresholds(20, bd("1.3"), bd("0"), bd("25"));
  }

  private static GraduationController.StrategyGraduation graduationControllerStrategyGraduation() {
    return new GraduationController.StrategyGraduation(
        UUID.randomUUID(), "manas-1", "Manas 1", "TAKE_ELIGIBLE", 25, bd("5000"), bd("0.6"),
        bd("1.8"), bd("120.5"), bd("8.2"), List.of());
  }

  private static GraduationController.Promotion graduationControllerPromotion() {
    return new GraduationController.Promotion(
        UUID.randomUUID(), OffsetDateTime.now(), 25, bd("120.5"), bd("1.9"), bd("8.2"));
  }

  private static GraduationService.Thresholds graduationServiceThresholds() {
    return new GraduationService.Thresholds(20, bd("1.3"), bd("0"), bd("25"));
  }

  private static GraduationService.StrategyGraduation graduationServiceStrategyGraduation() {
    return new GraduationService.StrategyGraduation(
        UUID.randomUUID(), "manas-1", "Manas 1", "TAKE_ELIGIBLE", 25, bd("5000"), bd("0.6"),
        bd("1.8"), bd("120.5"), bd("8.2"), List.of());
  }

  @Test
  void graduationControllerThresholdsAndStrategyGraduationAreTextualOnTheWire() {
    JsonNode t = wire(graduationControllerThresholds());
    for (String field : List.of("minProfitFactor", "minExpectancy", "maxDrawdownPct")) {
      assertThat(t.get(field).isTextual()).as(field).isTrue();
    }
    JsonNode g = wire(graduationControllerStrategyGraduation());
    for (String field :
        List.of("netRealized", "winRate", "profitFactor", "expectancy", "maxDrawdownPct")) {
      assertThat(g.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void graduationControllerPromotionDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(graduationControllerPromotion());
    for (String field : List.of("expectancy", "sharpe", "maxDrawdownPct")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void graduationServiceTwinsCarryTheSameAnnotationsAsTheController() {
    // GraduationService.Thresholds/StrategyGraduation share their simple name with
    // GraduationController's (springdoc collapses same-named schemas into ONE component), so
    // both twins must carry identical @Schema annotations even though only one is ever
    // actually reachable from a controller signature (verified: GraduationService's are not).
    JsonNode t = wire(graduationServiceThresholds());
    for (String field : List.of("minProfitFactor", "minExpectancy", "maxDrawdownPct")) {
      assertThat(t.get(field).isTextual()).as(field).isTrue();
    }
    JsonNode g = wire(graduationServiceStrategyGraduation());
    for (String field :
        List.of("netRealized", "winRate", "profitFactor", "expectancy", "maxDrawdownPct")) {
      assertThat(g.get(field).isTextual()).as(field).isTrue();
    }
  }

  // ---- swing/SwingSellDecisionService + SellDecisionRepository --------------------------------

  private static SwingSellDecisionService.SwingSellDecision swingSellDecision() {
    return new SwingSellDecisionService.SwingSellDecision(
        1L, "TCS", "VCP", 2, null, null, bd("3500.0"), bd("3600.0"), bd("2.86"), bd("3400.0"),
        bd("3550.0"), true, false, null, "HOLD");
  }

  private static SellDecisionRepository.SellDecisionRow sellDecisionRow() {
    return new SellDecisionRepository.SellDecisionRow(
        1L, "manas-1", LocalDate.now(), OffsetDateTime.now(), 2L, "NSE", "TCS", null, null, "VCP",
        null, bd("3500.0"), bd("3600.0"), bd("2.86"), bd("3400.0"), bd("3550.0"), true, false, null,
        "HOLD", null);
  }

  @Test
  void swingSellDecisionDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(swingSellDecision());
    for (String field :
        List.of("entryPrice", "currentPrice", "unrealizedPct", "stopLevel", "trailLevel")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void sellDecisionRowDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(sellDecisionRow());
    for (String field :
        List.of("entryPrice", "currentPrice", "unrealizedPct", "stopLevel", "trailLevel")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  // ---- signals/SignalRejectionRepository + ShadowPositionRepository + SignalViews -----------

  private SignalRejectionRepository.RejectionRow rejectionRow() {
    return new SignalRejectionRepository.RejectionRow(
        1L, UUID.randomUUID(), "scalp-gap-theory-nifty", "NFO", "NIFTY26JUL24500CE", "3m", "BUY",
        "confluence-blocked", bd("0.55"), bd("0.65"), bd("0.10"), "below rail", bd("0.61"),
        bd("0.65"), null, dataHealth(), true, OffsetDateTime.now(), OffsetDateTime.now());
  }

  private static ShadowPositionRepository.VariantSummary variantSummary() {
    return new ShadowPositionRepository.VariantSummary(
        "baseline", 2, 8, 5, 3, bd("120.5"), bd("9500.0"), 0);
  }

  private static SignalViews.SignalDto signalDto() {
    return new SignalViews.SignalDto(
        1L, UUID.randomUUID(), "NFO", "NIFTY26JUL24500CE", "3m", "ENTRY", "BUY", bd("101.5"),
        bd("95.0"), bd("120.0"), bd("0.82"), null, "FIRED", OffsetDateTime.now(), null, bd("50"),
        null, null, null, null);
  }

  @Test
  void signalRejectionRowDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(rejectionRow());
    for (String field :
        List.of(
            "blockingOperand", "blockingThreshold", "blockingMargin", "compositeScore",
            "compositeThreshold")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void shadowVariantSummaryDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(variantSummary());
    assertThat(node.get("pnlPoints").isTextual()).as("pnlPoints").isTrue();
    assertThat(node.get("pnlNet").isTextual()).as("pnlNet").isTrue();
  }

  @Test
  void signalDtoDecimalsAreTextualOnTheWire() {
    JsonNode node = wire(signalDto());
    for (String field :
        List.of("entryPrice", "stopLoss", "target", "compositeScore", "suggestedQty")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  // ---- signals/ShadowVariantsController ------------------------------------------------------

  private static ShadowVariantsController.RailOverrideView railOverrideView(
      String rail, boolean disable, BigDecimal threshold, String passWhen) {
    return new ShadowVariantsController.RailOverrideView(rail, disable, threshold, passWhen);
  }

  private static ShadowVariantsController.VariantSpecView variantSpecView() {
    return new ShadowVariantsController.VariantSpecView(
        List.of(railOverrideView("r", false, bd("2.0"), "gte")), bd("0.68"), null);
  }

  @Test
  void shadowVariantRailOverrideAndSpecDecimalsAreTextualOnTheWire() {
    JsonNode rail = wire(railOverrideView("relative-volume-floor", true, bd("1.5"), null));
    assertThat(rail.get("threshold").isTextual()).as("threshold").isTrue();

    JsonNode spec = wire(variantSpecView());
    assertThat(spec.get("compositeThreshold").isTextual()).as("compositeThreshold").isTrue();
    assertThat(spec.get("rails").get(0).get("threshold").isTextual()).as("rails[0].threshold").isTrue();
  }

  // ---- paper/PaperViews -----------------------------------------------------------------------

  private static PaperViews.EquityPoint equityPoint() {
    return new PaperViews.EquityPoint("2026-07-31", bd("150000.00"));
  }

  private static PaperViews.PnlSummary pnlSummary() {
    return new PaperViews.PnlSummary(bd("12500.50"), 42, bd("0.55"), bd("297.63"));
  }

  @Test
  void equityPointAndPnlSummaryDecimalsAreTextualOnTheWire() {
    JsonNode point = wire(equityPoint());
    assertThat(point.get("equity").isTextual()).as("equity").isTrue();

    JsonNode summary = wire(pnlSummary());
    for (String field : List.of("realizedTotal", "winRate", "expectancy")) {
      assertThat(summary.get(field).isTextual()).as(field).isTrue();
    }
  }

  // ---- paper/PaperMarginController -------------------------------------------------------------

  /**
   * The ONE {@code new MarginHeat(...)} site. Both shapes below delegate here, so this record obeys
   * the same one-constructor-site guarantee as every other in this file — cross-vendor review caught
   * it as the single record that still had two, which would have left a widening needing two edits
   * and quietly falsified the promise documented at the top of this class.
   */
  private static PaperMarginController.MarginHeat marginHeat(
      boolean priced, String reason, BigDecimal spanMargin, BigDecimal exposureMargin,
      BigDecimal totalMargin, BigDecimal requiredMargin, BigDecimal finalMargin, int openPositions,
      int pricedLegs) {
    return new PaperMarginController.MarginHeat(
        priced, reason, spanMargin, exposureMargin, totalMargin, requiredMargin, finalMargin,
        openPositions, pricedLegs, OffsetDateTime.now());
  }

  private static PaperMarginController.MarginHeat marginHeatPriced(
      BigDecimal spanMargin, BigDecimal exposureMargin, BigDecimal totalMargin,
      BigDecimal requiredMargin, BigDecimal finalMargin) {
    return marginHeat(
        true, null, spanMargin, exposureMargin, totalMargin, requiredMargin, finalMargin, 3, 3);
  }

  private static PaperMarginController.MarginHeat marginHeatUnpriced(String reason) {
    // MarginHeat.unpriced(...) is package-private (paper-package only); construct the same
    // all-null shape through the canonical helper above instead.
    return marginHeat(false, reason, null, null, null, null, null, 0, 0);
  }

  @Test
  void marginHeatDecimalsAreTextualOnTheWire() {
    JsonNode node =
        wire(marginHeatPriced(bd("337004.85"), bd("50000.0"), bd("387004.85"), bd("380000.0"), bd("188604.45")));
    for (String field :
        List.of("spanMargin", "exposureMargin", "totalMargin", "requiredMargin", "finalMargin")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void marginHeatUnpricedDecimalsStayPresentAndNull() {
    JsonNode node = wire(marginHeatUnpriced("no open positions"));
    for (String field :
        List.of("spanMargin", "exposureMargin", "totalMargin", "requiredMargin", "finalMargin")) {
      assertThat(node.has(field)).as(field + " present").isTrue();
      assertThat(node.get(field).isNull()).as(field + " null").isTrue();
    }
  }

  // ---- execution/OrderGateway -------------------------------------------------------------------

  private static OrderGateway.OrderbookEntry orderbookEntry() {
    return new OrderGateway.OrderbookEntry(
        "NIFTY26JUL24500CE", "NFO", "BUY", bd("50"), bd("101.5"), bd("95.0"), "LIMIT", "MIS",
        "OA123", "COMPLETE", "2026-07-31T09:20:00+05:30");
  }

  private static OrderGateway.PositionEntry positionEntry(
      String symbol, String exchange, String side, BigDecimal qty, BigDecimal avgPrice,
      BigDecimal ltp, BigDecimal mtmPnl) {
    return new OrderGateway.PositionEntry(symbol, exchange, side, qty, "MIS", avgPrice, ltp, mtmPnl);
  }

  private static OrderGateway.TradebookEntry tradebookEntry() {
    return new OrderGateway.TradebookEntry(
        "NIFTY26JUL24500CE", "NFO", "BUY", bd("50"), bd("101.5"), bd("5075.0"), "MIS", "OA123",
        "2026-07-31T09:20:00+05:30");
  }

  private static OrderGateway.Funds funds() {
    return new OrderGateway.Funds(
        "OK", bd("100000.0"), bd("0"), bd("475.0"), bd("120.0"), bd("50000.0"));
  }

  @Test
  void orderGatewayReadSurfaceDecimalsAreTextualOnTheWire() {
    JsonNode book = wire(orderbookEntry());
    for (String field : List.of("qty", "price", "triggerPrice")) {
      assertThat(book.get(field).isTextual()).as(field).isTrue();
    }

    JsonNode pos =
        wire(positionEntry("NIFTY26JUL24500CE", "NFO", "BUY", bd("50"), bd("101.5"), bd("110.0"), bd("475.0")));
    for (String field : List.of("qty", "avgPrice", "ltp", "mtmPnl")) {
      assertThat(pos.get(field).isTextual()).as(field).isTrue();
    }

    JsonNode trade = wire(tradebookEntry());
    for (String field : List.of("qty", "price", "tradeValue")) {
      assertThat(trade.get(field).isTextual()).as(field).isTrue();
    }

    JsonNode fundsNode = wire(funds());
    for (String field :
        List.of("availableCash", "collateral", "m2mRealized", "m2mUnrealized", "utilisedDebits")) {
      assertThat(fundsNode.get(field).isTextual()).as(field).isTrue();
    }
  }

  /**
   * {@code ltp}/{@code mtmPnl}/{@code qty} are nullable, not merely undocumented: {@code
   * RestOpenAlgoOrderReadGatewayTest#mapsPositionsAndDerivesSideFromSignedQty} feeds a REAL
   * OpenAlgo positionbook fixture that omits {@code ltp}/{@code pnl} on two of its three rows, and
   * that test asserts the resulting {@code PositionEntry.ltp()}/{@code .mtmPnl()} ARE null — this
   * test is the wire-shape half of the same claim: a null domain value must serialize as a PRESENT
   * JSON null, never be silently dropped, so the spec's {@code types = {"string", "null"}} is a
   * measured claim about a case a real fixture exercises, not an assumption.
   */
  @Test
  void positionEntryNullableDecimalsStayPresentAndNull() {
    JsonNode node = wire(positionEntry("INFY", "NSE", "SELL", bd("-1"), bd("0.00"), null, null));
    assertThat(node.get("avgPrice").isTextual()).as("avgPrice").isTrue();
    for (String field : List.of("ltp", "mtmPnl")) {
      assertThat(node.has(field)).as(field + " present").isTrue();
      assertThat(node.get(field).isNull()).as(field + " null").isTrue();
    }

    JsonNode flatQty = wire(positionEntry("YESBANK", "NSE", "FLAT", null, bd("0.00"), null, null));
    assertThat(flatQty.has("qty")).as("qty present").isTrue();
    assertThat(flatQty.get("qty").isNull()).as("qty null").isTrue();
  }

  @Test
  void orderGatewayFundsNotConfiguredDecimalsStayPresentAndNull() {
    JsonNode node = wire(OrderGateway.Funds.notConfigured());
    for (String field :
        List.of("availableCash", "collateral", "m2mRealized", "m2mUnrealized", "utilisedDebits")) {
      assertThat(node.has(field)).as(field + " present").isTrue();
      assertThat(node.get(field).isNull()).as(field + " null").isTrue();
    }
  }

  // ---- insights/Insight, StrategyEvidenceReader, RejectionReader, SignalCompareReader ----------

  private static Insight insight() {
    return new Insight(
        UUID.randomUUID(), OffsetDateTime.now(), "STRATEGY_EVIDENCE", "NOTICE", "STRATEGY",
        "title", "explanation", null, bd("0.42"), null, "TRUSTED", List.of(), "dk-1", null,
        false, "OPEN", null, "v1", "hash1");
  }

  private static StrategyEvidenceReader.OpenSell openSell() {
    return new StrategyEvidenceReader.OpenSell(
        1L, LocalDate.now(), "TCS", "SELL (target)", bd("6.4"), false);
  }

  private static RejectionReader.FiredRow firedRow() {
    return new RejectionReader.FiredRow(
        1L, "NIFTY26JUL24500CE", "BUY", bd("0.82"), 4, 5, OffsetDateTime.now());
  }

  private static RejectionReader.RejectedRow rejectedRow() {
    return new RejectionReader.RejectedRow(
        2L, "NIFTY26JUL24500CE", "BUY", bd("0.55"), bd("0.65"), "confluence-blocked", 2, 5,
        OffsetDateTime.now());
  }

  private static RejectionReader.Contrast contrast() {
    return new RejectionReader.Contrast(10, 20, bd("0.80"), bd("0.55"), bd("0.9"), bd("0.4"));
  }

  private static SignalCompareReader.ComponentPoint componentPoint() {
    return new SignalCompareReader.ComponentPoint("oi_confluence", bd("0.3"), bd("0.9"));
  }

  private static SignalCompareReader.CompareColumn compareColumn() {
    return new SignalCompareReader.CompareColumn(
        1L, "NIFTY26JUL24500CE", "BUY", "scalper", "manas-1", bd("0.82"), "HIGH",
        List.of(componentPoint()), bd("2350.0"), "~₹15,000", bd("2.4"), bd("101.5"), bd("95.0"),
        bd("120.0"), "TRUSTED", List.of(), "SCORED");
  }

  private static SignalCompareReader.TicketPrefill ticketPrefill() {
    return new SignalCompareReader.TicketPrefill(
        1L, "NFO", "NIFTY26JUL24500CE", "BUY", 50L, bd("95.0"), bd("120.0"));
  }

  @Test
  void insightPriorityIsTextualOnTheWire() {
    JsonNode node = wire(insight());
    assertThat(node.get("priority").isTextual()).as("priority").isTrue();
  }

  @Test
  void openSellUnrealizedPctIsTextualOnTheWire() {
    JsonNode node = wire(openSell());
    assertThat(node.get("unrealizedPct").isTextual()).as("unrealizedPct").isTrue();
  }

  @Test
  void firedAndRejectedRowAndContrastDecimalsAreTextualOnTheWire() {
    JsonNode fired = wire(firedRow());
    assertThat(fired.get("composite").isTextual()).as("composite").isTrue();

    JsonNode rejected = wire(rejectedRow());
    assertThat(rejected.get("composite").isTextual()).as("rejected composite").isTrue();
    assertThat(rejected.get("threshold").isTextual()).as("rejected threshold").isTrue();

    JsonNode contrast = wire(contrast());
    for (String field :
        List.of(
            "meanCompositeFired", "meanCompositeRejected", "meanSupportRatioFired",
            "meanSupportRatioRejected")) {
      assertThat(contrast.get(field).isTextual()).as(field).isTrue();
    }
  }

  @Test
  void compareColumnAndComponentPointAndTicketPrefillDecimalsAreTextualOnTheWire() {
    JsonNode componentNode = wire(componentPoint());
    assertThat(componentNode.get("points").isTextual()).as("points").isTrue();
    assertThat(componentNode.get("c").isTextual()).as("c").isTrue();

    JsonNode column = wire(compareColumn());
    for (String field :
        List.of("priority", "optionLegCost", "riskReward", "entryPrice", "stopLoss", "target")) {
      assertThat(column.get(field).isTextual()).as(field).isTrue();
    }

    JsonNode ticket = wire(ticketPrefill());
    assertThat(ticket.get("stopLoss").isTextual()).as("ticket stopLoss").isTrue();
    assertThat(ticket.get("target").isTextual()).as("ticket target").isTrue();
  }

  // ---- paper/PaperAccountService (incl. the two Map<String,BigDecimal> value schemas) ---------

  private static PaperAccountService.AccountDto accountDto() {
    return new PaperAccountService.AccountDto(
        bd("150000.00"), bd("162500.50"), bd("165000.00"), bd("12500.50"), bd("2500.00"),
        bd("475.00"), 3, bd("50000.00"),
        Map.of("OPTION_LONG", bd("2350.00")),
        Map.of("FUTURES", bd("12.5")),
        0);
  }

  @Test
  void accountDtoDecimalsIncludingMapValuesAreTextualOnTheWire() {
    JsonNode node = wire(accountDto());
    for (String field :
        List.of(
            "startingCapital", "cash", "equity", "realized", "unrealized", "dayPnl",
            "capitalUsed")) {
      assertThat(node.get(field).isTextual()).as(field).isTrue();
    }
    assertThat(node.get("usageByClass").get("OPTION_LONG").isTextual())
        .as("usageByClass map value")
        .isTrue();
    assertThat(node.get("marginPercents").get("FUTURES").isTextual())
        .as("marginPercents map value")
        .isTrue();
  }

  // ---- the integral/boolean/string contrast -----------------------------------------------------

  /**
   * The other half of the constraint: {@code long}/{@code int}/{@code Long}/{@code Integer} counts
   * are NOT string-serialized, so the spec must keep advertising them as {@code integer}. Lives in
   * its own test on purpose — sharing a method with the decimal assertions would let an earlier
   * failure mask it, and it is the assertion that stays GREEN when the platform serializer is
   * removed.
   */
  @Test
  void everyCountAndIdStaysAnIntegralNumber() {
    JsonNode trade = wire(tradeDto());
    assertThat(trade.get("id").isIntegralNumber()).as("id").isTrue();
    assertThat(trade.get("qty").isIntegralNumber()).as("qty").isTrue();

    JsonNode graduation = wire(graduationControllerStrategyGraduation());
    assertThat(graduation.get("trades").isIntegralNumber()).as("trades").isTrue();

    JsonNode variant = wire(variantSummary());
    assertThat(variant.get("open").isIntegralNumber()).as("open").isTrue();
    assertThat(variant.get("closed").isIntegralNumber()).as("closed").isTrue();
    assertThat(variant.get("unpriced").isIntegralNumber()).as("unpriced").isTrue();

    JsonNode heat = wire(marginHeatPriced(bd("1"), bd("1"), bd("1"), bd("1"), bd("1")));
    assertThat(heat.get("openPositions").isIntegralNumber()).as("openPositions").isTrue();
    assertThat(heat.get("pricedLegs").isIntegralNumber()).as("pricedLegs").isTrue();
  }

  /**
   * The far edge of the scope boundary. {@code PaperController.OrderBody}/{@code BracketBody}/
   * {@code CloseBody}/{@code AccountBody} are the only decimals still typed {@code number} in this
   * service's spec, and there {@code number} is TRUE: they are REQUEST-only (never returned by any
   * endpoint — verified over the captured spec's request/response reachability graph), the platform
   * customizes SERIALIZATION only, and Jackson's stock {@code BigDecimal} deserializer still accepts
   * a JSON number. Retyping them would be the same class of lie pointing the other way.
   */
  @Test
  void requestSideDecimalsStillAcceptPlainJsonNumbers() throws Exception {
    PaperController.OrderBody body =
        mapper.readValue(
            "{\"exchange\":\"NFO\",\"tradingsymbol\":\"NIFTY26JUL24500CE\",\"side\":\"BUY\","
                + "\"qty\":50,\"price\":101.5,\"stopLoss\":95,\"takeProfit\":120.25}",
            PaperController.OrderBody.class);
    assertThat(body.price()).isEqualByComparingTo("101.5");
    assertThat(body.stopLoss()).isEqualByComparingTo("95");
    assertThat(body.takeProfit()).isEqualByComparingTo("120.25");
  }
}
