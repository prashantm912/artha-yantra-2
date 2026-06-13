package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.strategyschema.ParameterPaths;
import in.arthayantra.strategyschema.ParameterPaths.ParsedPath;
import java.util.Iterator;
import java.util.Map;

/**
 * Applies a TRIAL job's {@code paramsOverride} onto the pinned version's config before replay
 * (§D.6 optimizer loop) — the service-side re-validation the closed grammar requires (§D.12): both
 * optimizer-service (at submission) and backtest-service (here, again) enforce the SAME grammar, so
 * a path string can never reach an arbitrary object graph. The patched config is transient — it is
 * NEVER persisted as a strategy version. A path outside the grammar, or one that does not resolve to
 * an existing leaf, is rejected with {@code 400 INVALID_PARAMETER_PATH}.
 */
public final class TrialOverrides {

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
