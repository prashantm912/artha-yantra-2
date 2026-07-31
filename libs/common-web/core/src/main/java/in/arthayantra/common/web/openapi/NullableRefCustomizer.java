package in.arthayantra.common.web.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.Ordered;

/**
 * Rewrites swagger-core's nullable {@code $ref} sibling into an OpenAPI 3.1 union that validators and
 * generated TypeScript clients understand.
 *
 * <p>swagger-core emits {@code $ref} plus a {@code types} set for {@code @Schema(types = {"object",
 * "null"})}. OpenAPI 3.1 applies that sibling, but OpenAPI 3.0 ignores it, so the assembled contract
 * must carry the honest {@code anyOf} shape instead.
 */
public class NullableRefCustomizer implements GlobalOpenApiCustomizer, Ordered {

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 1;
  }

  @Override
  public void customise(OpenAPI openApi) {
    if (openApi == null) {
      return;
    }
    Map<String, Schema> schemas =
        openApi.getComponents() == null ? null : openApi.getComponents().getSchemas();
    if (schemas == null || schemas.isEmpty() || openApi.getPaths() == null) {
      return;
    }
    // RESPONSE-ONLY, same doctrine as ResponseRequiredCustomizer (whose reachability walk this
    // reuses). Rewriting every component globally — and walking request bodies and parameters —
    // published request-side nullability the endpoint contract never asserted. A schema reachable
    // from BOTH a response and a request is left alone: correctness beats coverage.
    Set<String> responseOnly = ResponseRequiredCustomizer.responseOnlyNames(openApi, schemas);
    for (String name : responseOnly) {
      Schema<?> schema = schemas.get(name);
      if (schema != null) {
        schemas.put(name, rewriteSchema(schema));
      }
    }
    openApi
        .getPaths()
        .values()
        .forEach(path -> path.readOperations().forEach(NullableRefCustomizer::rewriteOperation));
  }

  /** Responses only — a request body or parameter must never be rewritten (see {@link #customise}). */
  private static void rewriteOperation(Operation operation) {
    if (operation.getResponses() != null) {
      operation.getResponses().values().forEach(NullableRefCustomizer::rewriteResponse);
    }
  }

  private static void rewriteResponse(ApiResponse response) {
    if (response == null) {
      return;
    }
    rewriteContent(response.getContent());
    if (response.getHeaders() != null) {
      response.getHeaders().values().forEach(NullableRefCustomizer::rewriteHeader);
    }
  }



  private static void rewriteHeader(Header header) {
    if (header == null) {
      return;
    }
    header.setSchema(rewriteSchema(header.getSchema()));
    rewriteContent(header.getContent());
  }

  private static void rewriteContent(Content content) {
    if (content == null) {
      return;
    }
    content.values().forEach(mediaType -> mediaType.setSchema(rewriteSchema(mediaType.getSchema())));
  }

  private static Schema<?> rewriteSchema(Schema<?> schema) {
    if (schema == null) {
      return null;
    }
    String ref = schema.get$ref();
    Set<String> types = schema.getTypes();
    if (ref != null && types != null && types.contains("null")) {
      schema.set$ref(null);
      schema.setTypes(null);
      schema.setAnyOf(
          List.of(new Schema<>().$ref(ref), new Schema<>().types(Set.of("null"))));
      return schema;
    }

    // A nullable ENUM needs null in the enum LIST too, not merely in `types`. Under JSON Schema
    // 2020-12 (which OpenAPI 3.1 uses) `type` and `enum` BOTH apply, so `type: ["string","null"]`
    // beside `enum: [<4 strings>]` REJECTS null — the type admits it and the enum forbids it, and a
    // strict validator refuses a response the server legitimately emits. swagger-core does not emit
    // the null member for a Java enum, so it is appended here. Found on the BankAnalysisCell
    // interpretation field (cross-vendor review, 2026-07-30), which is null for each bank's first
    // point. Response-only, same doctrine as the $ref rewrite above.
    appendNullEnumMember(schema);

    schema.setItems(rewriteSchema(schema.getItems()));
    if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
      schema.setAdditionalProperties(rewriteSchema(additionalProperties));
    }
    schema.setNot(rewriteSchema(schema.getNot()));
    rewriteProperties(schema.getProperties());
    rewriteSchemas(schema.getAllOf());
    rewriteSchemas(schema.getAnyOf());
    rewriteSchemas(schema.getOneOf());
    return schema;
  }

  /** Adds a literal {@code null} to a nullable schema's enum list; no-op when it is absent or present. */
  @SuppressWarnings("unchecked")
  private static void appendNullEnumMember(Schema<?> schema) {
    Set<String> types = schema.getTypes();
    List<?> values = schema.getEnum();
    if (types == null || !types.contains("null") || values == null || values.isEmpty()) {
      return;
    }
    if (!values.contains(null)) {
      ((Schema<Object>) schema).addEnumItemObject(null);
    }
  }

  private static void rewriteProperties(Map<String, Schema> properties) {
    if (properties != null) {
      properties.replaceAll((name, schema) -> rewriteSchema(schema));
    }
  }

  private static void rewriteSchemas(List<Schema> schemas) {
    if (schemas != null) {
      for (int i = 0; i < schemas.size(); i++) {
        schemas.set(i, rewriteSchema(schemas.get(i)));
      }
    }
  }
}
