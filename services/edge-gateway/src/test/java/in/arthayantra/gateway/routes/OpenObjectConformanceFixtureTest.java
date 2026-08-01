package in.arthayantra.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Cross-language conformance pin for {@code contracts/fixtures/open-object-conformance.json}.
 *
 * <p><b>Why this file exists, and why it does not (yet) call the production ratchet.</b> The
 * open-object ratchet exists in two independent implementations of the same schema walk —
 * {@code SpecOpenObjectRatchetTest} here and {@code test_open_object_ratchet.py} in
 * optimizer-service — sharing an "is this schema open" predicate and a location-string grammar by
 * convention and code review only. Cross-vendor review round 2 on the PR that introduced both
 * (#1196) found BOTH implementations returned "closed" for an annotation-only schema like
 * {@code {"title": "..."}}, which is semantically as open as {@code {}} — one bug, two places,
 * found once. This fixture pins the DOCTRINE (schema shape → open/closed, and the location-string
 * form) as first-principles-derived data, so a future drift between the two suites is caught by
 * BOTH independently reading the same file rather than by a reviewer noticing.
 *
 * <p>#1196 was still an open, actively-changing PR when this file was authored — its two target
 * files do not exist on this branch's base. This test therefore carries its OWN reference copy of
 * {@link #isOpenObject(JsonNode)}, independently derived from doctrine for every row in the
 * fixture and empirically checked (by reflection, against the actual committed #1196 code at the
 * time of writing) rather than imported, since production code is not on this branch to import.
 * When #1196 lands, the intended follow-up is for {@code SpecOpenObjectRatchetTest} and {@code
 * test_open_object_ratchet.py} to read this SAME fixture directly (ideally by exposing their
 * predicate as testable and asserting equality against it, retiring the duplicate below) —
 * tracked as an open doubt in the PR that introduces this file, not silently assumed done here.
 *
 * <p><b>Correction history, kept honest rather than quietly folded away.</b> This fixture's first
 * draft got two things wrong, both caught by cross-vendor review, neither by hand-tracing after
 * the fact: (1) it claimed a live disagreement between #1196's Java and Python predicates over
 * {@code {"title": "..."}}, based on reading an ACTIVELY-CHANGING worktree rather than running the
 * committed code — importing and calling the real function immediately falsified it, both sides
 * agree and match this fixture. (2) it classified {@code required}/{@code minProperties}/{@code
 * patternProperties} as pinning on mere PRESENCE and {@code format} as always constraining; review
 * ruled the first three are constraints only at a NON-vacuous value and {@code format} is never a
 * constraint by default (OpenAPI 3.1 / JSON Schema 2020-12: format is annotation-only unless the
 * format-assertion vocabulary is explicitly active, which neither springdoc nor pydantic opt
 * into). Both fixes are reflected in {@link #isOpenObject(JsonNode)} and pinned by fixture rows.
 */
class OpenObjectConformanceFixtureTest {

  private static final Path FIXTURE =
      findRepoRoot().resolve("contracts/fixtures/open-object-conformance.json");

  @Test
  void everyPredicateCaseMatchesTheFixture() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode fixture = mapper.readTree(Files.readString(FIXTURE));
    for (JsonNode row : fixture.get("predicateCases")) {
      String id = row.get("id").asText();
      boolean expectedOpen = row.get("expectedOpen").asBoolean();
      boolean actualOpen = isOpenObject(row.get("schema"));
      assertThat(actualOpen)
          .withFailMessage(
              "predicateCases[%s] expected isOpenObject=%s but got %s for schema %s",
              id, expectedOpen, actualOpen, row.get("schema"))
          .isEqualTo(expectedOpen);
    }
  }

  @Test
  void locationGrammarPatternAcceptsPositiveExamplesAndRejectsNegativeOnes() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode fixture = mapper.readTree(Files.readString(FIXTURE));
    JsonNode grammar = fixture.get("locationGrammar");
    Pattern pattern = Pattern.compile(grammar.get("pattern").asText());

    for (JsonNode example : grammar.get("positiveExamples")) {
      String location = example.get("location").asText();
      assertThat(pattern.matcher(location).matches())
          .withFailMessage("expected the location-grammar pattern to MATCH: %s", location)
          .isTrue();
    }
    for (JsonNode example : grammar.get("negativeExamples")) {
      String location = example.get("location").asText();
      assertThat(pattern.matcher(location).matches())
          .withFailMessage(
              "expected the location-grammar pattern to REJECT: %s (%s)",
              location, example.get("reason").asText())
          .isFalse();
    }
  }

  /**
   * Reference copy of the is-open predicate. See the class javadoc for why a copy exists at all
   * instead of a direct call, and for the correction history: two rounds of cross-vendor review on
   * #1196 found this predicate mistaking a keyword's mere PRESENCE for a real constraint —
   * {@code required}/{@code minProperties}/{@code patternProperties} at their VACUOUS values
   * ({@code []}/{@code 0}/{@code {}}) constrain nothing, and {@code format} constrains nothing at
   * ANY value (OpenAPI 3.1 + JSON Schema 2020-12: format is annotation-only by default). Both fixes
   * are reflected below and pinned by fixture rows named for them.
   */
  private static boolean isOpenObject(JsonNode node) {
    if (PINS_CONTENTS.stream().anyMatch(node::has)) {
      return false;
    }
    if (hasNonVacuousRequired(node)
        || hasNonVacuousMinProperties(node)
        || hasNonVacuousPatternProperties(node)) {
      return false;
    }
    JsonNode properties = node.get("properties");
    if (properties != null && !properties.isEmpty()) {
      return false;
    }
    JsonNode additional = node.get("additionalProperties");
    if (additional != null
        && !((additional.isBoolean() && additional.booleanValue())
            || (additional.isObject() && additional.isEmpty()))) {
      return false;
    }
    JsonNode type = node.get("type");
    boolean declaresObject =
        type != null
            && (type.isArray()
                ? stream(type).anyMatch(t -> "object".equals(t.asText()))
                : "object".equals(type.asText()));
    if (type != null) {
      return declaresObject;
    }
    if (additional != null) {
      return true;
    }
    return CONSTRAINING.stream().noneMatch(node::has);
  }

  /** True when {@code required} names at least one key — an empty list constrains nothing. */
  private static boolean hasNonVacuousRequired(JsonNode node) {
    JsonNode required = node.get("required");
    return required != null && !required.isEmpty();
  }

  /** True when {@code minProperties} sits above its trivial, always-true floor of zero. */
  private static boolean hasNonVacuousMinProperties(JsonNode node) {
    JsonNode minProperties = node.get("minProperties");
    return minProperties != null && minProperties.asInt(0) > 0;
  }

  /** True when {@code patternProperties} names at least one pattern — an empty map matches none. */
  private static boolean hasNonVacuousPatternProperties(JsonNode node) {
    JsonNode patternProperties = node.get("patternProperties");
    return patternProperties != null && !patternProperties.isEmpty();
  }

  /**
   * Keywords that ALWAYS pin what an object may CONTAIN, at any value. {@code required}, {@code
   * minProperties} and {@code patternProperties} are handled above instead, VALUE-aware, because
   * each has a vacuous value that constrains nothing.
   */
  private static final List<String> PINS_CONTENTS =
      List.of(
          "$ref",
          "allOf",
          "anyOf",
          "oneOf",
          "not",
          "items",
          "prefixItems",
          "enum",
          "const",
          "discriminator",
          "propertyNames",
          "dependentSchemas",
          "unevaluatedProperties",
          "maxProperties");

  /**
   * Every keyword that says ANYTHING about a value; unrecognised keywords fail OPEN. {@code
   * format} is deliberately absent — annotation-only by default, never a constraint — and {@code
   * properties} is deliberately absent too: by the time this fallback runs it can only be absent
   * or vacuously empty (a non-empty value already returned false above), so testing its presence
   * here would silently reintroduce the exact bug the explicit check above exists to fix.
   */
  private static final List<String> CONSTRAINING =
      Stream.concat(
              PINS_CONTENTS.stream(),
              Stream.of(
                  "type",
                  "additionalProperties",
                  "unevaluatedItems",
                  "contains",
                  "dependentRequired",
                  "if",
                  "then",
                  "else",
                  "multipleOf",
                  "maximum",
                  "exclusiveMaximum",
                  "minimum",
                  "exclusiveMinimum",
                  "maxLength",
                  "minLength",
                  "pattern",
                  "maxItems",
                  "minItems",
                  "uniqueItems",
                  "maxContains",
                  "minContains"))
          .toList();

  private static Stream<JsonNode> stream(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }

  private static Path findRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("contracts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("repo root (contracts/ dir) not found above the module dir");
    }
    return dir;
  }
}
