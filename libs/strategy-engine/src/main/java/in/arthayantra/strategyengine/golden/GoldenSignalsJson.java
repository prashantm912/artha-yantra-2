package in.arthayantra.strategyengine.golden;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyengine.eval.ScoreBreakdown;
import java.math.RoundingMode;
import java.util.List;

/**
 * The FROZEN expected-signal encoding (docs/golden-vectors.md §3): keys in the documented order,
 * exact decimal strings (scale 4 for composites/scores/weights as shown in the frozen example),
 * no insignificant whitespace — both parity halves serialize through THIS writer before byte
 * comparison.
 */
public final class GoldenSignalsJson {

  /** One emitted signal event (the tick-wise runner's output). */
  public record SignalEvent(
      String timestamp,
      String exchange,
      String tradingsymbol,
      String direction,
      ScoreBreakdown breakdown) {}

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private GoldenSignalsJson() {}

  /** Serializes the full fixture document in the frozen shape. */
  public static String write(
      String strategyFile, List<String> candleFiles, List<SignalEvent> signals) {
    ObjectNode root = F.objectNode();
    root.put("fixtureFormat", 1);
    root.put("strategy", strategyFile);
    ArrayNode candles = F.arrayNode();
    candleFiles.forEach(candles::add);
    root.set("candles", candles);
    ArrayNode out = F.arrayNode();
    for (SignalEvent event : signals) {
      ObjectNode node = F.objectNode();
      node.put("timestamp", event.timestamp());
      node.put("exchange", event.exchange());
      node.put("tradingsymbol", event.tradingsymbol());
      node.put("direction", event.direction());
      node.put("composite", scale4(event.breakdown().composite()));
      ObjectNode breakdown = F.objectNode();
      for (ScoreBreakdown.IndicatorEntry entry : event.breakdown().indicators()) {
        ObjectNode line = F.objectNode();
        line.put("score", entry.score() == null ? "null" : scale4(entry.score()));
        line.put("weight", scale4(entry.weight()));
        line.put("activated", entry.activated());
        breakdown.set(entry.alias(), line);
      }
      node.set("breakdown", breakdown);
      out.add(node);
    }
    root.set("signals", out);
    return root.toString();
  }

  private static String scale4(java.math.BigDecimal value) {
    return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
  }
}
