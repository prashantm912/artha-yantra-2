package in.arthayantra.strategyengine.config;

import in.arthayantra.strategyengine.normalize.NormalizeSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The compiled, evaluation-ready view of one strategy version — produced by
 * {@link StrategyCompiler} from the canonical config tree. Holds exactly what the engine needs
 * at bar close; lifecycle metadata stays in the registry.
 */
public record StrategyDefinition(
    String id,
    String version,
    String primaryTimeframe,
    List<String> additionalTimeframes,
    List<IndicatorSpec> indicators,
    Direction direction,
    GateNode gate,
    Scoring scoring,
    List<ExitRuleSpec> exitRules,
    SizingSpec sizing,
    Session session) {

  /** Entry direction. */
  public enum Direction {
    LONG,
    SHORT,
    BOTH
  }

  /** One indicator instance. */
  public record IndicatorSpec(
      String name,
      String alias,
      String timeframe,
      Map<String, Object> params,
      BigDecimal weight,
      boolean optional,
      NormalizeSpec normalize,
      InstrumentRef instrument) {

    /** True when this instance participates in the composite (has a normalize mapping). */
    public boolean scoring() {
      return normalize != null;
    }
  }

  /** A context-override instrument reference (A7 [FP-19]). */
  public record InstrumentRef(String exchange, String tradingsymbol) {}

  /** entry_rules.scoring with A1 defaults applied. */
  public record Scoring(
      BigDecimal threshold, BigDecimal optionalMinScore, BigDecimal optionalGateMargin) {}

  /** One typed exit rule. */
  public record ExitRuleSpec(String type, Map<String, Object> params) {}

  /** risk.position_sizing. */
  public record SizingSpec(String method, Map<String, Object> params) {}

  /** The session knobs the engine reads (A7 [FP-5]/[FP-6]). */
  public record Session(
      String style,
      String windowFrom,
      String windowTo,
      String squareOff,
      String preCloseAt,
      String fillTiming,
      boolean exitIntrabar) {}
}
