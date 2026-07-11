package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed parameter-path whitelist grammar (COMMON §12.5 / §C-2.16):
 *
 * <pre>
 * path            := indicator-path | exit-path | scoring-path | risk-path | gate-path | risk-cap-path
 * indicator-path  := "indicators[" selector "].params." ident
 * exit-path       := "exit_rules[" selector "].params." ident
 * scoring-path    := "entry_rules.scoring." ident
 * risk-path       := "risk.position_sizing." ident
 * gate-path       := "entry_rules.gate." ident      # ident = the LHS operand of a literal-RHS gate comparison
 * risk-cap-path   := "risk." ("max_positions" | "max_positions_per_underlying")
 * selector        := "alias=" ident | "type=" ident | int
 * </pre>
 *
 * <p>Resolution is a pure walk of the parsed config tree — selectors compare literally against
 * the {@code alias}/{@code type} fields, no reflection, no expression evaluation. Bare positional
 * selectors are accepted but linted (§C-2.4).
 *
 * <p>The gate-path and risk-cap-path productions (EVO §13 row 21) widen the tunable surface to
 * gate-expression constants and the position caps. A gate-path addresses the single gate-tree
 * comparison whose left operand is {@code ident} and whose right side is a numeric <em>literal</em>
 * (e.g. {@code entry_rules.gate.vol} → {@code "vol > 1.2"}); it resolves iff exactly one such
 * comparison exists — comparisons against another operand ({@code px > sma20}) carry no tunable
 * constant and never resolve.
 */
public final class ParameterPaths {

  private static final Pattern PATH =
      Pattern.compile(
          "^(?:(indicators|exit_rules)\\[(?:(alias|type)=([a-z][a-z0-9_]*)|([0-9]+))\\]"
              + "\\.params\\.([a-z][a-z0-9_]*)"
              + "|entry_rules\\.scoring\\.([a-z][a-z0-9_]*)"
              + "|risk\\.position_sizing\\.([a-z][a-z0-9_]*)"
              + "|entry_rules\\.gate\\.([a-z][a-z0-9_]*)"
              + "|risk\\.(max_positions_per_underlying|max_positions))$");

  /** A gate-tree comparison against a numeric literal: captures the left operand (the addressable LHS). */
  private static final Pattern GATE_LITERAL =
      Pattern.compile("^\\s*([a-z][a-z0-9_]*)\\s*(?:>=|<=|==|!=|>|<)\\s*-?[0-9]+(?:\\.[0-9]+)?\\s*$");

  /** A parsed path; {@code section} is the grammar branch that matched. */
  public record ParsedPath(
      Section section, String selectorKind, String selectorValue, String field) {

    /** True when the selector is a bare positional index (accepted but linted). */
    public boolean positional() {
      return "index".equals(selectorKind);
    }
  }

  /** Grammar branches. */
  public enum Section {
    INDICATORS,
    EXIT_RULES,
    SCORING,
    POSITION_SIZING,
    GATE,
    RISK_LIMIT
  }

  private ParameterPaths() {}

  /** Parses against the closed grammar; empty when the path is outside it. */
  public static Optional<ParsedPath> parse(String path) {
    if (path == null) {
      return Optional.empty();
    }
    Matcher m = PATH.matcher(path);
    if (!m.matches()) {
      return Optional.empty();
    }
    if (m.group(6) != null) {
      return Optional.of(new ParsedPath(Section.SCORING, null, null, m.group(6)));
    }
    if (m.group(7) != null) {
      return Optional.of(new ParsedPath(Section.POSITION_SIZING, null, null, m.group(7)));
    }
    if (m.group(8) != null) {
      return Optional.of(new ParsedPath(Section.GATE, null, null, m.group(8)));
    }
    if (m.group(9) != null) {
      return Optional.of(new ParsedPath(Section.RISK_LIMIT, null, null, m.group(9)));
    }
    Section section = "indicators".equals(m.group(1)) ? Section.INDICATORS : Section.EXIT_RULES;
    if (m.group(4) != null) {
      return Optional.of(new ParsedPath(section, "index", m.group(4), m.group(5)));
    }
    return Optional.of(new ParsedPath(section, m.group(2), m.group(3), m.group(5)));
  }

  /** True when the path's target exists in the validated config tree. */
  public static boolean resolves(JsonNode config, ParsedPath path) {
    return switch (path.section()) {
      case SCORING -> config.path("entry_rules").path("scoring").has(path.field());
      case POSITION_SIZING -> {
        JsonNode sizing = config.path("risk").path("position_sizing");
        yield sizing.has(path.field()) || sizing.path("params").has(path.field());
      }
      case INDICATORS, EXIT_RULES -> {
        String array = path.section() == Section.INDICATORS ? "indicators" : "exit_rules";
        JsonNode element = selectElement(config.path(array), path);
        yield element != null && element.path("params").has(path.field());
      }
      // A gate constant resolves iff EXACTLY ONE gate-tree comparison has this LHS and a numeric
      // literal RHS — zero (no such constant) or many (ambiguous which to tune) is a loud non-resolve.
      case GATE -> countGateLiterals(config.path("entry_rules").path("gate"), path.field()) == 1;
      // A risk cap resolves iff the enumerated scalar exists in this config (both caps are optional).
      case RISK_LIMIT -> config.path("risk").path(path.field()).isNumber();
    };
  }

  /** Counts the gate-tree comparisons whose left operand is {@code lhs} and whose RHS is a literal. */
  private static int countGateLiterals(JsonNode node, String lhs) {
    if (node.isTextual()) {
      Matcher m = GATE_LITERAL.matcher(node.asText());
      return m.matches() && m.group(1).equals(lhs) ? 1 : 0;
    }
    int total = 0;
    if (node.has("all")) {
      for (JsonNode child : node.get("all")) {
        total += countGateLiterals(child, lhs);
      }
    } else if (node.has("any")) {
      for (JsonNode child : node.get("any")) {
        total += countGateLiterals(child, lhs);
      }
    } else if (node.has("not")) {
      total += countGateLiterals(node.get("not"), lhs);
    }
    return total;
  }

  private static JsonNode selectElement(JsonNode array, ParsedPath path) {
    if (!array.isArray()) {
      return null;
    }
    if (path.positional()) {
      int index = Integer.parseInt(path.selectorValue());
      return index < array.size() ? array.get(index) : null;
    }
    String key = path.selectorKind();
    for (JsonNode element : array) {
      if (path.selectorValue().equals(element.path(key).asText(null))) {
        return element;
      }
    }
    return null;
  }
}
