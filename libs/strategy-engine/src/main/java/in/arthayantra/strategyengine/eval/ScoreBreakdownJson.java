package in.arthayantra.strategyengine.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyschema.CanonicalJson;
import java.math.BigDecimal;
import java.util.Map;

/**
 * The ONE serialization of the §C-2.6 contract — used verbatim by the live engine (persisted
 * JSONB, REST payloads, the signals channel) and later by replay. Canonical form (sorted keys,
 * normalized numbers) through the same writer as config checksums, so byte-identity live vs
 * replay is a string comparison.
 */
public final class ScoreBreakdownJson {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private ScoreBreakdownJson() {}

  /** Canonical JSON text. */
  public static String write(ScoreBreakdown breakdown) {
    return CanonicalJson.write(toNode(breakdown));
  }

  /** The Jackson tree (for JSONB persistence and channel payload embedding). */
  public static JsonNode toNode(ScoreBreakdown breakdown) {
    ObjectNode root = F.objectNode();
    root.set("composite", decimal(breakdown.composite()));
    root.set("threshold", decimal(breakdown.threshold()));
    root.put("passed", breakdown.passed());
    root.set("requiredComposite", decimal(breakdown.requiredComposite()));
    root.set("optionalMinScore", decimal(breakdown.optionalMinScore()));
    root.set("optionalGateMargin", decimal(breakdown.optionalGateMargin()));
    root.set("weightDenominator", decimal(breakdown.weightDenominator()));
    root.set("gate", gateNode(breakdown.gate()));
    ArrayNode indicators = F.arrayNode();
    for (ScoreBreakdown.IndicatorEntry entry : breakdown.indicators()) {
      ObjectNode node = F.objectNode();
      node.put("alias", entry.alias());
      node.put("name", entry.name());
      node.put("timeframe", entry.timeframe());
      node.set("score", decimal(entry.score()));
      node.set("weight", decimal(entry.weight()));
      node.set("contribution", decimal(entry.contribution()));
      node.put("optional", entry.optional());
      node.put("activated", entry.activated());
      node.put("activationReason", entry.activationReason().name());
      node.set("rawValue", decimal(entry.rawValue()));
      ObjectNode params = F.objectNode();
      for (Map.Entry<String, Object> param : entry.params().entrySet()) {
        Object value = param.getValue();
        if (value instanceof BigDecimal bd) {
          params.set(param.getKey(), decimal(bd));
        } else if (value instanceof Long l) {
          params.put(param.getKey(), l);
        } else if (value instanceof Boolean b) {
          params.put(param.getKey(), b);
        } else {
          params.put(param.getKey(), String.valueOf(value));
        }
      }
      node.set("params", params);
      indicators.add(node);
    }
    root.set("indicators", indicators);
    return root;
  }

  private static JsonNode gateNode(ScoreBreakdown.GateResult result) {
    ObjectNode node = F.objectNode();
    node.put("kind", result.kind());
    if (result.rule() != null) {
      node.put("rule", result.rule());
    }
    node.put("passed", result.passed());
    if (!result.operands().isEmpty()) {
      ObjectNode operands = F.objectNode();
      result.operands().forEach((name, value) -> operands.set(name, decimal(value)));
      node.set("operands", operands);
    }
    if (!result.children().isEmpty()) {
      ArrayNode children = F.arrayNode();
      result.children().forEach(child -> children.add(gateNode(child)));
      node.set("children", children);
    }
    return node;
  }

  private static JsonNode decimal(BigDecimal value) {
    // Exact-decimal STRING, the end-to-end convention (decimal.ts / SignalPublisher prices). A JSON
    // number would be rounded by the browser's JSON.parse before formatDecimal runs, and a sub-1e-6
    // magnitude would arrive in exponential notation decimal.ts cannot parse. Normalize identically
    // to CanonicalJson so the canonical write() form stays byte-stable (and quoted).
    return value == null ? F.nullNode() : F.textNode(CanonicalJson.normalize(value).toPlainString());
  }
}
