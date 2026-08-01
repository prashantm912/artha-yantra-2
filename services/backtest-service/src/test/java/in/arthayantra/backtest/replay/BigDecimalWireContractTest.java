package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.counterfactual.CounterfactualResult;
import in.arthayantra.backtest.replay.counterfactual.ExitKnobs;
import in.arthayantra.common.web.jackson.ArthaJacksonAutoConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Pins the WIRE FORM of every {@code BigDecimal} on a backtest RESPONSE record, so the committed
 * OpenAPI spec's {@code type: string} is a measured claim rather than an annotation nobody checked.
 *
 * <p>Why this test has to exist: {@code ArthaJacksonAutoConfiguration} routes every {@code
 * BigDecimal} through {@code ToStringSerializer} platform-wide, so these fields are decimal STRINGS
 * on the wire — but springdoc infers {@code number} from the Java type, and {@code @Schema(types =
 * ...)} UNIONS with that inference instead of replacing it. Only {@code type = "string"} replaces
 * it. A test that read values with {@code asText()} would pass against a number node too and prove
 * nothing; every decimal assertion here is {@code isTextual()}.
 *
 * <p>The mapper is built the way Boot builds it (common-web-core's {@code
 * ArthaJacksonConventionsTest} is the same instrument), so deleting the {@code
 * serializerByType(BigDecimal.class, ...)} line turns the decimal assertions RED while the
 * integral-count assertions stay GREEN — the contrast that shows they discriminate.
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

  @Test
  void everyTradeRowDecimalIsTextualOnTheWire() {
    JsonNode node = wire(tradeItem(bd("101.5000"), bd("2350.75"), bd("99.00"), bd("140.25")));

    assertThat(node.get("entryPrice").isTextual()).as("entryPrice").isTrue();
    assertThat(node.get("exitPrice").isTextual()).as("exitPrice").isTrue();
    assertThat(node.get("pnl").isTextual()).as("pnl").isTrue();
    assertThat(node.get("pnlPct").isTextual()).as("pnlPct").isTrue();
    assertThat(node.get("stopLoss").isTextual()).as("stopLoss").isTrue();
    assertThat(node.get("takeProfit").isTextual()).as("takeProfit").isTrue();

    // Trailing zeros survive ToStringSerializer — the reason the wire form is a string at all.
    assertThat(node.get("entryPrice").textValue()).isEqualTo("101.5000");
  }

  @Test
  void theNullableTradeRowDecimalsStayPresentAndNull() {
    JsonNode node = wire(tradeItem(bd("101.5000"), null, null, null));

    assertThat(node.has("exitPrice")).isTrue();
    assertThat(node.get("exitPrice").isNull()).isTrue();
    assertThat(node.get("stopLoss").isNull()).isTrue();
    assertThat(node.get("takeProfit").isNull()).isTrue();
  }

  @Test
  void everySwingReportCardDecimalIsTextualOnTheWire() {
    JsonNode node =
        wire(
            new SwingReportCard(
                5,
                3,
                2,
                bd("60.0000"),
                bd("10.0000"),
                bd("-4.0000"),
                bd("2.5000"),
                bd("4.4000"),
                bd("9.6000"),
                true,
                "A"));

    assertThat(node.get("battingAvgPct").isTextual()).as("battingAvgPct").isTrue();
    assertThat(node.get("avgWinPct").isTextual()).as("avgWinPct").isTrue();
    assertThat(node.get("avgLossPct").isTextual()).as("avgLossPct").isTrue();
    assertThat(node.get("payoffRatio").isTextual()).as("payoffRatio").isTrue();
    assertThat(node.get("expectancyPct").isTextual()).as("expectancyPct").isTrue();
    assertThat(node.get("avgBarsHeld").isTextual()).as("avgBarsHeld").isTrue();
  }

  @Test
  void everyCounterfactualVariantDecimalIsTextualOnTheWire() {
    JsonNode node =
        wire(
            new CounterfactualResult.VariantResult(
                "tp35-sl20",
                12,
                bd("18450.00"),
                bd("246.50"),
                bd("0.5833"),
                bd("1537.50"),
                bd("-4200.00"),
                Map.of("TAKE_PROFIT", 7)));

    assertThat(node.get("netPnlInr").isTextual()).as("netPnlInr").isTrue();
    assertThat(node.get("grossPremiumPoints").isTextual()).as("grossPremiumPoints").isTrue();
    assertThat(node.get("winRate").isTextual()).as("winRate").isTrue();
    assertThat(node.get("expectancyInr").isTextual()).as("expectancyInr").isTrue();
    assertThat(node.get("maxDrawdownInr").isTextual()).as("maxDrawdownInr").isTrue();
  }

  @Test
  void theDecisionTraceCompositeIsTextualOnTheWire() {
    JsonNode node =
        wire(
            new DecisionTraceCollector.Trace(
                LocalDate.of(2026, 7, 31), "composite_below_threshold", 42, bd("0.6150"), null,
                null));

    assertThat(node.get("maxComposite").isTextual()).as("maxComposite").isTrue();
  }

  /**
   * The other half of the constraint: {@code Long}/{@code Integer} counts are NOT string-serialized,
   * so the spec must keep advertising them as {@code integer}. This lives in its own test on purpose
   * — sharing a method with the decimal assertions would let an earlier failure mask it, and it is
   * the assertion that stays GREEN when the platform serializer is removed.
   */
  @Test
  void everyCountStaysAnIntegralNumber() {
    JsonNode trade = wire(tradeItem(bd("101.5000"), bd("2350.75"), bd("99.00"), bd("140.25")));
    assertThat(trade.get("seq").isIntegralNumber()).as("seq").isTrue();
    assertThat(trade.get("qty").isIntegralNumber()).as("qty").isTrue();
    assertThat(trade.get("barsHeld").isIntegralNumber()).as("barsHeld").isTrue();

    JsonNode card =
        wire(
            new SwingReportCard(
                5, 3, 2, bd("60.0000"), bd("10.0000"), bd("-4.0000"), bd("2.5000"), bd("4.4000"),
                bd("9.6000"), true, "A"));
    assertThat(card.get("trades").isIntegralNumber()).as("trades").isTrue();
    assertThat(card.get("wins").isIntegralNumber()).as("wins").isTrue();
    assertThat(card.get("losses").isIntegralNumber()).as("losses").isTrue();

    JsonNode variant =
        wire(
            new CounterfactualResult.VariantResult(
                "tp35-sl20", 12, bd("18450.00"), bd("246.50"), bd("0.5833"), bd("1537.50"),
                bd("-4200.00"), Map.of("TAKE_PROFIT", 7)));
    assertThat(variant.get("tradeCount").isIntegralNumber()).as("tradeCount").isTrue();

    JsonNode trace =
        wire(
            new DecisionTraceCollector.Trace(
                LocalDate.of(2026, 7, 31), "composite_below_threshold", 42, bd("0.6150"), null,
                null));
    assertThat(trace.get("bars").isIntegralNumber()).as("bars").isTrue();
  }

  @Test
  void theDecisionTraceCompositeStaysPresentAndNullWhenAbsent() {
    JsonNode node =
        wire(
            new DecisionTraceCollector.Trace(
                LocalDate.of(2026, 7, 31), "no_bars", 0, null, null, null));

    assertThat(node.has("maxComposite")).isTrue();
    assertThat(node.get("maxComposite").isNull()).isTrue();
  }

  /**
   * The far edge of the scope boundary. The decimals still typed {@code number} in this service's
   * spec are all on REQUEST-only schemas, and there {@code number} is TRUE: the platform customizes
   * SERIALIZATION only, so Jackson's stock BigDecimal deserializer still accepts a JSON number.
   * Retyping those to {@code string} would be the same class of lie pointing the other way, which is
   * why they were deliberately left alone.
   */
  @Test
  void requestSideDecimalsStillAcceptPlainJsonNumbers() throws Exception {
    ExitKnobs knobs =
        mapper.readValue(
            "{\"takeProfitPct\":35,\"stopLossPct\":20.5,\"timeStopBars\":30}", ExitKnobs.class);

    assertThat(knobs.takeProfitPct()).isEqualByComparingTo("35");
    assertThat(knobs.stopLossPct()).isEqualByComparingTo("20.5");
    assertThat(knobs.timeStopBars()).isEqualTo(30);
  }

  private static BacktestViews.BacktestTradeItem tradeItem(
      BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
    return new BacktestViews.BacktestTradeItem(
        1,
        "BUY",
        50L,
        "2026-07-31T09:20:00+05:30",
        entryPrice,
        exitPrice == null ? null : "2026-07-31T09:50:00+05:30",
        exitPrice,
        bd("2350.75"),
        bd("12.3400"),
        "take_profit",
        30,
        null,
        null,
        "NFO",
        "NIFTY26JUL24500CE",
        stopLoss,
        takeProfit);
  }
}
