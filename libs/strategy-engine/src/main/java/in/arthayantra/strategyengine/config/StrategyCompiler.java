package in.arthayantra.strategyengine.config;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.strategyengine.normalize.NormalizeSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical config tree → {@link StrategyDefinition}. Assumes a schema-valid document
 * (strategy-schema validates upstream); anything structurally surprising here is a hard error,
 * never a silent default — except the A1/A7 documented defaults (optional_min_score 0.6,
 * optional_gate_margin 0.15, pre_close_at 15:20, fill_timing by style, exit_intrabar by primary
 * timeframe).
 */
public final class StrategyCompiler {

  private static final Pattern EXPRESSION =
      Pattern.compile(
          "^\\s*([a-z][a-z0-9_]*)\\s*(>=|<=|==|!=|>|<)\\s*([a-z][a-z0-9_]*|-?[0-9]+(?:\\.[0-9]+)?)\\s*$");
  private static final Pattern CROSS_CALL =
      Pattern.compile(
          "^\\s*(crossover|crossunder)\\(\\s*([a-z][a-z0-9_]*)\\s*,\\s*([a-z][a-z0-9_]*)\\s*\\)\\s*$");

  private StrategyCompiler() {}

  /** Compiles a schema-valid canonical tree. */
  public static StrategyDefinition compile(JsonNode config) {
    String primary = config.path("timeframes").path("primary").asText();
    List<String> additional = new ArrayList<>();
    config.path("timeframes").path("additional").forEach(n -> additional.add(n.asText()));

    List<StrategyDefinition.IndicatorSpec> indicators = new ArrayList<>();
    for (JsonNode node : config.path("indicators")) {
      indicators.add(compileIndicator(node));
    }

    JsonNode entry = config.path("entry_rules");
    StrategyDefinition.Direction direction =
        StrategyDefinition.Direction.valueOf(
            entry.path("direction").asText().toUpperCase(java.util.Locale.ROOT));
    GateNode gate = compileGate(entry.path("gate"));
    JsonNode scoringNode = entry.path("scoring");
    StrategyDefinition.Scoring scoring =
        new StrategyDefinition.Scoring(
            decimal(scoringNode.path("threshold")),
            scoringNode.has("optional_min_score")
                ? decimal(scoringNode.get("optional_min_score"))
                : new BigDecimal("0.6"),
            scoringNode.has("optional_gate_margin")
                ? decimal(scoringNode.get("optional_gate_margin"))
                : new BigDecimal("0.15"));

    List<StrategyDefinition.ExitRuleSpec> exitRules = new ArrayList<>();
    for (JsonNode rule : config.path("exit_rules")) {
      exitRules.add(
          new StrategyDefinition.ExitRuleSpec(
              rule.path("type").asText(), paramsMap(rule.path("params"))));
    }

    JsonNode risk = config.path("risk");
    StrategyDefinition.SizingSpec sizing =
        new StrategyDefinition.SizingSpec(
            risk.path("position_sizing").path("method").asText(),
            paramsMap(risk.path("position_sizing").path("params")));

    return new StrategyDefinition(
        config.path("id").asText(),
        config.path("version").asText(),
        primary,
        List.copyOf(additional),
        List.copyOf(indicators),
        direction,
        gate,
        scoring,
        List.copyOf(exitRules),
        sizing,
        compileSession(risk.path("session"), primary));
  }

  private static StrategyDefinition.IndicatorSpec compileIndicator(JsonNode node) {
    NormalizeSpec normalize = node.has("normalize") ? compileNormalize(node.get("normalize")) : null;
    StrategyDefinition.InstrumentRef instrument = null;
    if (node.has("instrument")) {
      instrument =
          new StrategyDefinition.InstrumentRef(
              node.get("instrument").path("exchange").asText(),
              node.get("instrument").path("tradingsymbol").asText());
    }
    return new StrategyDefinition.IndicatorSpec(
        node.path("name").asText(),
        node.path("alias").asText(),
        node.path("timeframe").asText(),
        paramsMap(node.path("params")),
        node.has("weight") ? decimal(node.get("weight")) : BigDecimal.ONE,
        node.path("optional").asBoolean(false),
        normalize,
        instrument);
  }

  private static NormalizeSpec compileNormalize(JsonNode node) {
    String type = node.path("type").asText();
    return switch (type) {
      case "linear" -> NormalizeSpec.linear(decimal(node.get("from")), decimal(node.get("to")));
      case "step" -> {
        List<NormalizeSpec.Band> bands = new ArrayList<>();
        for (JsonNode band : node.path("bands")) {
          bands.add(
              new NormalizeSpec.Band(
                  band.has("upto") ? decimal(band.get("upto")) : null,
                  decimal(band.get("score"))));
        }
        yield NormalizeSpec.step(bands);
      }
      case "direction" -> NormalizeSpec.direction();
      case "rsi_momentum" -> NormalizeSpec.rsiMomentum();
      default -> throw new IllegalArgumentException("unknown normalize type '" + type + "'");
    };
  }

  /** Compiles a gate node (also used for signal_exit rule strings). */
  public static GateNode compileGate(JsonNode node) {
    if (node.isTextual()) {
      return compileLeafText(node.asText());
    }
    if (node.has("all")) {
      List<GateNode> children = new ArrayList<>();
      node.get("all").forEach(child -> children.add(compileGate(child)));
      return new GateNode.All(List.copyOf(children));
    }
    if (node.has("any")) {
      List<GateNode> children = new ArrayList<>();
      node.get("any").forEach(child -> children.add(compileGate(child)));
      return new GateNode.Any(List.copyOf(children));
    }
    if (node.has("not")) {
      return new GateNode.Not(compileGate(node.get("not")));
    }
    if (node.has("crossover")) {
      JsonNode pair = node.get("crossover");
      return new GateNode.Crossover(pair.path("fast").asText(), pair.path("slow").asText());
    }
    if (node.has("crossunder")) {
      JsonNode pair = node.get("crossunder");
      return new GateNode.Crossunder(pair.path("fast").asText(), pair.path("slow").asText());
    }
    throw new IllegalArgumentException("unrecognized gate node: " + node);
  }

  /** Compiles a leaf string: threshold expression or crossover()/crossunder() call form. */
  public static GateNode compileLeafText(String text) {
    Matcher cross = CROSS_CALL.matcher(text);
    if (cross.matches()) {
      return "crossover".equals(cross.group(1))
          ? new GateNode.Crossover(cross.group(2), cross.group(3))
          : new GateNode.Crossunder(cross.group(2), cross.group(3));
    }
    Matcher m = EXPRESSION.matcher(text);
    if (!m.matches()) {
      throw new IllegalArgumentException("expression outside the closed grammar: " + text);
    }
    String right = m.group(3);
    boolean literal = right.isEmpty() || !Character.isLetter(right.charAt(0));
    return new GateNode.Expression(
        m.group(1),
        GateNode.Comparison.of(m.group(2)),
        literal ? null : right,
        literal ? new BigDecimal(right) : null);
  }

  private static StrategyDefinition.Session compileSession(JsonNode node, String primary) {
    String style = node.path("style").asText("intraday");
    String fillTiming =
        node.has("fill_timing")
            ? node.get("fill_timing").asText()
            : ("btst".equals(style) ? "at_close" : "next_open");
    boolean exitIntrabar =
        node.has("exit_intrabar")
            ? node.get("exit_intrabar").asBoolean()
            : !"1m".equals(primary);
    return new StrategyDefinition.Session(
        style,
        node.path("window").path("from").asText(null),
        node.path("window").path("to").asText(null),
        node.path("square_off").asText(null),
        node.path("pre_close_at").asText("15:20"),
        fillTiming,
        exitIntrabar);
  }

  private static Map<String, Object> paramsMap(JsonNode params) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (params != null && params.isObject()) {
      params
          .properties()
          .forEach(
              entry -> {
                JsonNode v = entry.getValue();
                if (v.isNumber()) {
                  map.put(
                      entry.getKey(),
                      v.isIntegralNumber() ? (Object) v.longValue() : v.decimalValue());
                } else if (v.isBoolean()) {
                  map.put(entry.getKey(), v.booleanValue());
                } else {
                  map.put(entry.getKey(), v.asText());
                }
              });
    }
    return Map.copyOf(map);
  }

  private static BigDecimal decimal(JsonNode node) {
    return new BigDecimal(node.asText());
  }
}
