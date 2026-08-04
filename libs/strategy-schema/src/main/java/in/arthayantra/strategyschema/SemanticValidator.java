package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks the JSON Schema cannot express (§C-2.13): alias uniqueness, rule operands
 * referencing declared aliases (or the built-in series), every referenced indicator timeframe
 * declared, optimize paths resolving against the closed grammar, plus the lints — bare positional
 * selectors and optional indicators that can never activate. Instrument-existence checks (context
 * overrides, registry names) are deliberately NOT here — they are server-side checks at
 * save/publish (Q2 — §C-2.9).
 */
public final class SemanticValidator {

  /** Built-in series usable as expression operands (§C-2.4). */
  static final Set<String> BUILTINS = Set.of("close", "volume", "vwap");

  private static final Pattern EXPRESSION =
      Pattern.compile(
          "^\\s*([a-z][a-z0-9_]*)\\s*(>=|<=|==|!=|>|<)\\s*([a-z][a-z0-9_]*|-?[0-9]+(?:\\.[0-9]+)?)\\s*$");
  private static final Pattern CROSS_CALL =
      Pattern.compile(
          "^\\s*(crossover|crossunder)\\(\\s*([a-z][a-z0-9_]*)\\s*,\\s*([a-z][a-z0-9_]*)\\s*\\)\\s*$");
  private static final Pattern NUMERIC = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?$");

  private final List<ValidationIssue> errors = new ArrayList<>();
  private final List<ValidationIssue> warnings = new ArrayList<>();
  private final Set<String> aliases = new HashSet<>();
  private final Set<String> timeframes = new HashSet<>();

  private SemanticValidator() {}

  /** Runs every semantic check over a schema-valid config tree. */
  public static ValidationResult check(JsonNode config) {
    SemanticValidator v = new SemanticValidator();
    v.collectTimeframes(config);
    v.collectAliases(config);
    v.checkIndicators(config);
    v.checkGateTree(config.path("entry_rules").path("gate"), "/entry_rules/gate");
    v.checkExitRules(config);
    v.checkOptionsPlaneLevelBases(config);
    v.checkOptimizeParameters(config);
    return v.errors.isEmpty()
        ? ValidationResult.ok(v.warnings)
        : ValidationResult.failed(v.errors, v.warnings);
  }

  private void collectTimeframes(JsonNode config) {
    JsonNode tf = config.path("timeframes");
    if (tf.hasNonNull("primary")) {
      timeframes.add(tf.get("primary").asText());
    }
    tf.path("additional").forEach(node -> timeframes.add(node.asText()));
  }

  private void collectAliases(JsonNode config) {
    JsonNode indicators = config.path("indicators");
    for (int i = 0; i < indicators.size(); i++) {
      String alias = indicators.get(i).path("alias").asText(null);
      if (alias != null && !aliases.add(alias)) {
        errors.add(
            new ValidationIssue(
                "/indicators/" + i + "/alias", "duplicate alias '" + alias + "'"));
      }
    }
  }

  private void checkIndicators(JsonNode config) {
    JsonNode indicators = config.path("indicators");
    for (int i = 0; i < indicators.size(); i++) {
      JsonNode indicator = indicators.get(i);
      String timeframe = indicator.path("timeframe").asText(null);
      if (timeframe != null && !timeframes.contains(timeframe)) {
        errors.add(
            new ValidationIssue(
                "/indicators/" + i + "/timeframe",
                "timeframe '" + timeframe + "' is not declared under timeframes"));
      }
      if (indicator.path("optional").asBoolean(false) && !indicator.has("normalize")) {
        warnings.add(
            new ValidationIssue(
                "/indicators/" + i,
                "optional indicator '" + indicator.path("alias").asText("")
                    + "' has no normalize mapping and can never activate"));
      }
    }
  }

  private void checkGateTree(JsonNode node, String path) {
    if (node.isMissingNode()) {
      return;
    }
    if (node.isTextual()) {
      checkExpression(node.asText(), path);
      return;
    }
    if (node.isObject()) {
      if (node.has("all") || node.has("any")) {
        String key = node.has("all") ? "all" : "any";
        JsonNode children = node.get(key);
        for (int i = 0; i < children.size(); i++) {
          checkGateTree(children.get(i), path + "/" + key + "/" + i);
        }
        return;
      }
      if (node.has("not")) {
        checkGateTree(node.get("not"), path + "/not");
        return;
      }
      if (node.has("crossover") || node.has("crossunder")) {
        String key = node.has("crossover") ? "crossover" : "crossunder";
        JsonNode pair = node.get(key);
        requireAlias(pair.path("fast").asText(null), path + "/" + key + "/fast");
        requireAlias(pair.path("slow").asText(null), path + "/" + key + "/slow");
      }
    }
  }

  private void checkExpression(String expression, String path) {
    Matcher m = EXPRESSION.matcher(expression);
    if (!m.matches()) {
      errors.add(new ValidationIssue(path, "expression outside the closed grammar"));
      return;
    }
    requireOperand(m.group(1), path);
    String right = m.group(3);
    if (!NUMERIC.matcher(right).matches()) {
      requireOperand(right, path);
    }
  }

  private void requireOperand(String operand, String path) {
    if (!aliases.contains(operand) && !BUILTINS.contains(operand)) {
      errors.add(
          new ValidationIssue(
              path,
              "operand '" + operand + "' is neither a declared alias nor a built-in "
                  + "(close, volume, vwap)"));
    }
  }

  private void requireAlias(String alias, String path) {
    if (alias != null && !aliases.contains(alias)) {
      errors.add(new ValidationIssue(path, "'" + alias + "' is not a declared alias"));
    }
  }

  private void checkExitRules(JsonNode config) {
    JsonNode exitRules = config.path("exit_rules");
    for (int i = 0; i < exitRules.size(); i++) {
      JsonNode rule = exitRules.get(i);
      if (!"signal_exit".equals(rule.path("type").asText())) {
        continue;
      }
      String text = rule.path("params").path("rule").asText("");
      String path = "/exit_rules/" + i + "/params/rule";
      Matcher cross = CROSS_CALL.matcher(text);
      if (cross.matches()) {
        requireAlias(cross.group(2), path);
        requireAlias(cross.group(3), path);
      } else {
        checkExpression(text, path);
      }
    }
  }

  /**
   * An {@code options_of_underlying} strategy may not use {@code basis: percent} on a level rule
   * ({@code stop_loss} / {@code take_profit}) — the name is ambiguous on the only strategy kind
   * that has TWO price planes, and every consumer downstream resolves it differently.
   *
   * <p>{@code premium_pct} is the OPTION-premium basis: the live bracket chain
   * ({@code PremiumBracketRules}, {@code PaperSignalListener}) and the backtest's
   * {@code OptionsPremiumReplay} all key on that exact literal to build the premium bracket.
   * {@code percent} is the CASH-EQUITY basis (added in #539 for the Minervini 8% initial stop) and
   * reaches only {@code ExitEvaluator.levelDistance}, which resolves it against the INDEX entry
   * price. So on an options strategy {@code percent} silently means a percentage of a DIFFERENT
   * INSTRUMENT than an author writing it almost certainly intends, and builds no premium bracket.
   *
   * <p>The two downstream consumers disagree in OPPOSITE directions, which is what makes this
   * worth refusing rather than documenting: the premium plane honours {@code premium_pct} and
   * ignores {@code percent}, while {@code ScalperRisk.ENGINE_SIDE_STOP_BASES} counts
   * {@code percent} as an engine-fireable §0B bounding stop and excludes {@code premium_pct} as
   * "inert at index scale" — yet {@code levelDistance} computes both with the SAME formula
   * ({@code entryPrice × value ÷ 100}). A scalper whose only stop is {@code {percent, 25}}
   * therefore LOADS as bounded while having no enforceable stop on either plane: ~25% of the index
   * never fires intraday, and no bracket is built. That is precisely the unbounded-losing-option
   * state the §0B hard-stop rule exists to prevent.
   *
   * <p>Deliberately narrow: the other level bases stay legal here because they ARE enforced
   * index-side by {@code ExitEvaluator} ({@code atr_multiple}, {@code index_points}), so the
   * position is bounded even though they build no premium bracket either.
   */
  private void checkOptionsPlaneLevelBases(JsonNode config) {
    if (!"options_of_underlying".equals(config.path("universe").path("mode").asText())) {
      return;
    }
    JsonNode exitRules = config.path("exit_rules");
    for (int i = 0; i < exitRules.size(); i++) {
      JsonNode rule = exitRules.get(i);
      String type = rule.path("type").asText();
      if (!"stop_loss".equals(type) && !"take_profit".equals(type)) {
        continue;
      }
      if ("percent".equals(rule.path("params").path("basis").asText())) {
        errors.add(
            new ValidationIssue(
                "/exit_rules/" + i + "/params/basis",
                "'percent' is the cash-equity basis and is ambiguous on an options strategy: it"
                    + " resolves against the INDEX entry price and builds no premium bracket."
                    + " Use 'premium_pct' for an option-premium level, or"
                    + " 'index_points'/'atr_multiple' for an index-side level."));
      }
    }
  }

  private void checkOptimizeParameters(JsonNode config) {
    JsonNode parameters = config.path("backtest").path("optimize").path("parameters");
    for (int i = 0; i < parameters.size(); i++) {
      String raw = parameters.get(i).path("path").asText(null);
      String pointer = "/backtest/optimize/parameters/" + i + "/path";
      if (raw == null) {
        continue;
      }
      Optional<ParameterPaths.ParsedPath> parsed = ParameterPaths.parse(raw);
      if (parsed.isEmpty()) {
        errors.add(
            new ValidationIssue(
                pointer, "path is outside the closed whitelist grammar (COMMON 12.5)"));
        continue;
      }
      if (parsed.get().positional()) {
        warnings.add(
            new ValidationIssue(
                pointer,
                String.format(
                    Locale.ROOT,
                    "bare positional selector '%s' is fragile under reordering — prefer "
                        + "alias=/type= selectors",
                    raw)));
      }
      if (!ParameterPaths.resolves(config, parsed.get())) {
        errors.add(
            new ValidationIssue(pointer, "path does not resolve in this config: " + raw));
      }
    }
  }
}
