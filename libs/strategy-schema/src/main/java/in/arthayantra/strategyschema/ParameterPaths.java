package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed parameter-path whitelist grammar (COMMON §12.5 / §C-2.16):
 *
 * <pre>
 * path            := indicator-path | exit-path | scoring-path | risk-path
 * indicator-path  := "indicators[" selector "].params." ident
 * exit-path       := "exit_rules[" selector "].params." ident
 * scoring-path    := "entry_rules.scoring." ident
 * risk-path       := "risk.position_sizing." ident
 * selector        := "alias=" ident | "type=" ident | int
 * </pre>
 *
 * <p>Resolution is a pure walk of the parsed config tree — selectors compare literally against
 * the {@code alias}/{@code type} fields, no reflection, no expression evaluation. Bare positional
 * selectors are accepted but linted (§C-2.4).
 */
public final class ParameterPaths {

  private static final Pattern PATH =
      Pattern.compile(
          "^(?:(indicators|exit_rules)\\[(?:(alias|type)=([a-z][a-z0-9_]*)|([0-9]+))\\]"
              + "\\.params\\.([a-z][a-z0-9_]*)"
              + "|entry_rules\\.scoring\\.([a-z][a-z0-9_]*)"
              + "|risk\\.position_sizing\\.([a-z][a-z0-9_]*))$");

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
    POSITION_SIZING
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
    };
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
