package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyschema.ParameterPaths;
import in.arthayantra.strategyschema.ParameterPaths.ParsedPath;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a TRIAL job's {@code paramsOverride} onto the pinned version's config before replay
 * (§D.6 optimizer loop) — the service-side re-validation the closed grammar requires (§D.12): both
 * optimizer-service (at submission) and backtest-service (here, again) enforce the SAME grammar, so
 * a path string can never reach an arbitrary object graph. The patched config is transient — it is
 * NEVER persisted as a strategy version. A path outside the grammar, or one that does not resolve to
 * an existing leaf, is rejected with {@code 400 INVALID_PARAMETER_PATH}.
 */
public final class TrialOverrides {

  /** A gate-tree comparison against a numeric literal: LHS operand, comparison op, literal RHS. */
  private static final Pattern GATE_LITERAL =
      Pattern.compile("^\\s*([a-z][a-z0-9_]*)\\s*(>=|<=|==|!=|>|<)\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*$");

  private TrialOverrides() {}

  /**
   * Returns a deep copy of {@code baseConfig} with each {@code path: value} override applied.
   *
   * @param baseConfig the pinned version's validated config
   * @param overrides a JSON object mapping closed-grammar paths to scalar values
   * @return the patched config (a fresh node); {@code baseConfig} is left untouched
   */
  public static JsonNode apply(JsonNode baseConfig, JsonNode overrides) {
    ObjectNode patched = (ObjectNode) baseConfig.deepCopy();
    Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> override = fields.next();
      String path = override.getKey();
      ParsedPath parsed =
          ParameterPaths.parse(path)
              .orElseThrow(
                  () ->
                      new ApiException(
                          400,
                          ErrorCodes.INVALID_PARAMETER_PATH,
                          "parameter path not in the closed grammar: " + path));
      if (!ParameterPaths.resolves(patched, parsed)) {
        throw new ApiException(
            400,
            ErrorCodes.INVALID_PARAMETER_PATH,
            "parameter path does not resolve to an existing leaf: " + path);
      }
      setLeaf(patched, parsed, override.getValue());
    }
    return patched;
  }

  private static void setLeaf(ObjectNode config, ParsedPath path, JsonNode value) {
    switch (path.section()) {
      case SCORING ->
          ((ObjectNode) config.path("entry_rules").path("scoring")).set(path.field(), value);
      case POSITION_SIZING -> {
        ObjectNode sizing = (ObjectNode) config.path("risk").path("position_sizing");
        if (sizing.has(path.field())) {
          sizing.set(path.field(), value);
        } else {
          ((ObjectNode) sizing.path("params")).set(path.field(), value);
        }
      }
      case INDICATORS, EXIT_RULES -> {
        String array = path.section() == ParameterPaths.Section.INDICATORS ? "indicators" : "exit_rules";
        ObjectNode element = (ObjectNode) selectElement(config.path(array), path);
        ((ObjectNode) element.path("params")).set(path.field(), value);
      }
      // Rewrite the single gate-tree comparison with this LHS (resolves() guaranteed exactly one),
      // preserving the LHS + operator and swapping only the numeric literal. toPlainString() keeps it
      // in the gate grammar's `-?[0-9]+(\.[0-9]+)?` shape (no scientific notation).
      case GATE -> {
        if (!value.isNumber()) {
          throw new ApiException(
              400,
              ErrorCodes.INVALID_PARAMETER_PATH,
              "gate-constant override must be numeric: " + path.field());
        }
        ObjectNode entryRules = (ObjectNode) config.path("entry_rules");
        entryRules.set(
            "gate", rewriteGate(entryRules.path("gate"), path.field(), value.decimalValue().toPlainString()));
      }
      // The position caps are direct scalar leaves under `risk`.
      case RISK_LIMIT -> ((ObjectNode) config.path("risk")).set(path.field(), value);
    }
  }

  /**
   * Returns {@code node} with the sole gate comparison whose LHS is {@code lhs} rewritten to
   * {@code lhs op literal}. Containers ({@code all}/{@code any}/{@code not}) are walked in place; a
   * matching leaf becomes a fresh {@link TextNode} (so the root-is-a-single-expression case is
   * handled by the caller re-assigning the returned node). Non-matching nodes are returned as-is.
   */
  private static JsonNode rewriteGate(JsonNode node, String lhs, String literal) {
    if (node.isTextual()) {
      Matcher m = GATE_LITERAL.matcher(node.asText());
      if (m.matches() && m.group(1).equals(lhs)) {
        return TextNode.valueOf(lhs + " " + m.group(2) + " " + literal);
      }
      return node;
    }
    if (node.has("all")) {
      rewriteGateChildren((ArrayNode) node.get("all"), lhs, literal);
    } else if (node.has("any")) {
      rewriteGateChildren((ArrayNode) node.get("any"), lhs, literal);
    } else if (node.has("not")) {
      ((ObjectNode) node).set("not", rewriteGate(node.get("not"), lhs, literal));
    }
    return node;
  }

  private static void rewriteGateChildren(ArrayNode array, String lhs, String literal) {
    for (int i = 0; i < array.size(); i++) {
      array.set(i, rewriteGate(array.get(i), lhs, literal));
    }
  }

  private static JsonNode selectElement(JsonNode array, ParsedPath path) {
    if (path.positional()) {
      return array.get(Integer.parseInt(path.selectorValue()));
    }
    for (JsonNode element : array) {
      if (path.selectorValue().equals(element.path(path.selectorKind()).asText(null))) {
        return element;
      }
    }
    throw new ApiException(
        400, ErrorCodes.INVALID_PARAMETER_PATH, "selector did not match any element");
  }
}
