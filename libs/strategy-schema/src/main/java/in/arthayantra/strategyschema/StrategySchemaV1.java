package in.arthayantra.strategyschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The frozen schema document + the networknt draft-2020-12 validator (CD-4). The document is
 * served downstream byte-identically ({@link #documentText()} returns the classpath resource
 * verbatim — no runtime mutation, a Phase 18 acceptance criterion).
 */
public final class StrategySchemaV1 {

  /** The schema id served and stored on every version row. */
  public static final String SCHEMA_VERSION = "strategy-schema/v1";

  private static final String RESOURCE = "/strategy-schema/strategy-schema-v1.json";
  private static final String DOCUMENT_TEXT = readResource();
  private static final JsonSchema SCHEMA = compile();

  private StrategySchemaV1() {}

  /** The schema document exactly as committed (byte-identical serving). */
  public static String documentText() {
    return DOCUMENT_TEXT;
  }

  /** Validates a canonical config tree; returns pointer-anchored issues. */
  public static List<ValidationIssue> validate(JsonNode config) {
    Set<ValidationMessage> messages = SCHEMA.validate(config);
    List<ValidationIssue> issues = new ArrayList<>(messages.size());
    for (ValidationMessage message : messages) {
      issues.add(
          new ValidationIssue(message.getInstanceLocation().toString(), message.getMessage()));
    }
    issues.sort(
        java.util.Comparator.comparing(ValidationIssue::path)
            .thenComparing(ValidationIssue::message));
    return issues;
  }

  private static String readResource() {
    try (InputStream in = StrategySchemaV1.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("schema resource missing: " + RESOURCE);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read schema resource", e);
    }
  }

  private static JsonSchema compile() {
    SchemaValidatorsConfig config =
        SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).build();
    JsonSchemaFactory factory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    return factory.getSchema(DOCUMENT_TEXT, config);
  }
}
