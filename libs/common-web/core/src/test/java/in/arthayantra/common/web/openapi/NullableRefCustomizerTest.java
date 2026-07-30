package in.arthayantra.common.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link NullableRefCustomizer} — the nullable-ENUM half.
 *
 * <p>Under JSON Schema 2020-12 (which OpenAPI 3.1 uses) {@code type} and {@code enum} BOTH apply, so
 * {@code type: ["string","null"]} beside {@code enum: [<members>]} REJECTS null: the type admits it
 * and the enum forbids it, and a strict validator refuses a response the server legitimately emits.
 * swagger-core does not emit the null member for a Java enum, so the customizer appends it.
 *
 * <p>Found on {@code BankAnalysisCell.interpretation} (cross-vendor review, 2026-07-30), which is
 * null for each bank's first point because there is no previous bucket to classify against.
 */
class NullableRefCustomizerTest {

  private static final String CE = "application/json";

  @Test
  void appendsLiteralNullToANullableEnumSoStrictValidatorsAcceptTheRealResponse() {
    Schema<?> nullableEnum =
        new Schema<>().types(Set.of("string", "null"))._enum(new java.util.ArrayList<>(List.of("A", "B")));
    OpenAPI api = responseOnlyApi("Row", new Schema<>().addProperty("kind", nullableEnum));

    new NullableRefCustomizer().customise(api);

    assertThat(enumOf(api, "Row", "kind"))
        .as("the null member the type already promised")
        .containsExactly("A", "B", null);
  }

  @Test
  void leavesANonNullableEnumAlone() {
    Schema<?> plainEnum =
        new Schema<>().types(Set.of("string"))._enum(new java.util.ArrayList<>(List.of("A", "B")));
    OpenAPI api = responseOnlyApi("Row", new Schema<>().addProperty("kind", plainEnum));

    new NullableRefCustomizer().customise(api);

    assertThat(enumOf(api, "Row", "kind"))
        .as("a non-nullable enum must not gain a null member")
        .containsExactly("A", "B");
  }

  @Test
  void isIdempotentSoARepeatedCustomisePassDoesNotAppendNullTwice() {
    Schema<?> nullableEnum =
        new Schema<>().types(Set.of("string", "null"))._enum(new java.util.ArrayList<>(List.of("A")));
    OpenAPI api = responseOnlyApi("Row", new Schema<>().addProperty("kind", nullableEnum));

    NullableRefCustomizer customizer = new NullableRefCustomizer();
    customizer.customise(api);
    customizer.customise(api);

    assertThat(enumOf(api, "Row", "kind")).containsExactly("A", null);
  }

  /**
   * RESPONSE-ONLY doctrine, inherited from {@link ResponseRequiredCustomizer}: a schema reachable
   * from a REQUEST body must never be rewritten, because publishing request-side nullability asserts
   * something the endpoint contract never said. Correctness beats coverage.
   */
  @Test
  void neverRewritesASchemaReachableFromARequestBody() {
    Schema<?> nullableEnum =
        new Schema<>().types(Set.of("string", "null"))._enum(new java.util.ArrayList<>(List.of("A")));
    OpenAPI api = responseOnlyApi("Row", new Schema<>().addProperty("kind", nullableEnum));
    // the same component is now ALSO a request body — that makes it request-reachable
    api.getPaths()
        .get("/x")
        .getGet()
        .setRequestBody(
            new RequestBody()
                .content(
                    new Content()
                        .addMediaType(
                            CE, new MediaType().schema(new Schema<>().$ref("#/components/schemas/Row")))));

    new NullableRefCustomizer().customise(api);

    assertThat(enumOf(api, "Row", "kind"))
        .as("request-reachable schemas are left alone")
        .containsExactly("A");
  }

  private static OpenAPI responseOnlyApi(String name, Schema<?> component) {
    OpenAPI api = new OpenAPI();
    api.setComponents(new io.swagger.v3.oas.models.Components().addSchemas(name, component));
    api.setPaths(
        new Paths()
            .addPathItem(
                "/x",
                new PathItem()
                    .get(
                        new Operation()
                            .responses(
                                new ApiResponses()
                                    .addApiResponse(
                                        "200",
                                        new ApiResponse()
                                            .content(
                                                new Content()
                                                    .addMediaType(
                                                        CE,
                                                        new MediaType()
                                                            .schema(
                                                                new Schema<>()
                                                                    .$ref(
                                                                        "#/components/schemas/"
                                                                            + name)))))))));
    return api;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> enumOf(OpenAPI api, String component, String property) {
    Schema<?> schema =
        (Schema<?>) api.getComponents().getSchemas().get(component).getProperties().get(property);
    return (List<Object>) schema.getEnum();
  }
}
