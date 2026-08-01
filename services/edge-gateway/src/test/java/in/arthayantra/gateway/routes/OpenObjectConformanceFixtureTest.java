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
 * {@link #isOpenObject(JsonNode)}, calibrated against {@code SpecOpenObjectRatchetTest}'s
 * (already round-2-fixed) implementation and independently re-derived from doctrine for every row
 * in the fixture, rather than importing production code that is not there to import. When #1196
 * lands, the intended follow-up is for {@code SpecOpenObjectRatchetTest} and
 * {@code test_open_object_ratchet.py} to read this SAME fixture directly (ideally by exposing
 * their predicate as testable and asserting equality against it, retiring the duplicate below) —
 * tracked as an open doubt in the PR that introduces this file, not silently assumed done here.
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
   * Reference copy of the is-open predicate, calibrated against {@code
   * SpecOpenObjectRatchetTest}'s round-2-fixed implementation. See the class javadoc for why a
   * copy exists at all instead of a direct call.
   */
  private static boolean isOpenObject(JsonNode node) {
    if (PINS_CONTENTS.stream().anyMatch(node::has)) {
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

  /** Keywords that pin what an object may CONTAIN. Any of them present ⇒ not an open object. */
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
          "patternProperties",
          "propertyNames",
          "dependentSchemas",
          "unevaluatedProperties",
          "required",
          "minProperties",
          "maxProperties");

  /** Every keyword that says ANYTHING about a value; unrecognised keywords fail OPEN. */
  private static final List<String> CONSTRAINING =
      Stream.concat(
              PINS_CONTENTS.stream(),
              Stream.of(
                  "type",
                  "properties",
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
                  "format",
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
