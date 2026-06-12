package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The Phase 18 facade: YAML → canonical tree → schema validation → semantic validation, plus the
 * D18 canonicalization/checksum pipeline. Services depend on this class; the pieces stay
 * individually testable.
 */
public final class StrategyDocuments {

  /** A parsed document: canonical tree, canonical JSON text and its SHA-256. */
  public record Parsed(JsonNode config, String canonicalJson, String checksum) {}

  private StrategyDocuments() {}

  /** Parses + canonicalizes + checksums (no validation). Throws {@link StrategyParseException}. */
  public static Parsed parse(String yaml) {
    JsonNode config = YamlStrategyLoader.load(yaml);
    String canonical = CanonicalJson.write(config);
    return new Parsed(config, canonical, CanonicalJson.sha256Hex(canonical));
  }

  /**
   * Full validation: parse errors → single-issue failure; schema errors short-circuit the
   * semantic pass (semantics assume a schema-valid shape).
   */
  public static ValidationResult validate(String yaml) {
    JsonNode config;
    try {
      config = YamlStrategyLoader.load(yaml);
    } catch (StrategyParseException e) {
      return ValidationResult.failed(List.of(new ValidationIssue("", e.getMessage())), List.of());
    }
    List<ValidationIssue> schemaIssues = StrategySchemaV1.validate(config);
    if (!schemaIssues.isEmpty()) {
      return ValidationResult.failed(schemaIssues, List.of());
    }
    return SemanticValidator.check(config);
  }

  /** Validation over an already-parsed canonical tree (registry re-validation at publish). */
  public static ValidationResult validateTree(JsonNode config) {
    List<ValidationIssue> schemaIssues = StrategySchemaV1.validate(config);
    if (!schemaIssues.isEmpty()) {
      return ValidationResult.failed(schemaIssues, List.of());
    }
    return SemanticValidator.check(config);
  }

  /** Convenience: all issues (errors then warnings) of a result, for envelope details. */
  public static List<ValidationIssue> flatten(ValidationResult result) {
    List<ValidationIssue> all = new ArrayList<>(result.errors());
    all.addAll(result.warnings());
    return all;
  }
}
