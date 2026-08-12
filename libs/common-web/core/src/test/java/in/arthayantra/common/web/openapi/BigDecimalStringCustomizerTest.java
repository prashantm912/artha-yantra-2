package in.arthayantra.common.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.Components;
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
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link BigDecimalStringCustomizer} — the decimals-are-strings rule.
 *
 * <p>{@code ArthaJacksonAutoConfiguration} registers {@code ToStringSerializer} for {@link
 * BigDecimal}, so a decimal is a JSON string on the wire while springdoc infers {@code number}.
 *
 * <p>These tests carry the load the captured specs CANNOT: at {@code bf93ebf7} the annotation sweep
 * had already retyped every response-side decimal by hand, so the committed spec is byte-identical
 * with or without this customizer and proves nothing about its behaviour. The position axis in
 * particular is only observable here.
 */
class BigDecimalStringCustomizerTest {

  private static final String CE = "application/json";

  record Money(BigDecimal price, BigDecimal fee) {}

  record Strikes(List<BigDecimal> strikes) {}

  record Mixed(BigDecimal amount, Double ratio, Long count) {}

  @Test
  void retypesAResponseDecimalToStringBecauseThatIsWhatTheSerializerWrites() {
    OpenAPI api = responseOnlyApi("Money", numeric("price", "fee"));

    customize(api, Money.class);

    assertThat(typeOf(api, "Money", "price")).containsExactly("string");
    assertThat(typeOf(api, "Money", "fee")).containsExactly("string");
  }

  /**
   * THE reason this is a customizer and not a plain {@link ModelConverter}. Nothing overrides
   * Jackson's stock {@code BigDecimal} deserializer, so a request decimal genuinely accepts a JSON
   * number; {@code number} is TRUE there and retyping it would publish a new lie in the other
   * direction. Measured at {@code bf93ebf7}: all 28 surviving {@code type: number} nodes in the
   * committed specs are request surfaces, so a position-blind converter would have moved every one.
   */
  @Test
  void neverRetypesADecimalReachableFromARequestBody() {
    OpenAPI api = responseOnlyApi("Money", numeric("price", "fee"));
    api.getPaths()
        .get("/x")
        .getGet()
        .setRequestBody(
            new RequestBody()
                .content(
                    new Content()
                        .addMediaType(
                            CE, new MediaType().schema(ref("Money")))));

    customize(api, Money.class);

    assertThat(typeOf(api, "Money", "price"))
        .as("a request decimal accepts a JSON number — number is not a lie there")
        .containsExactly("number");
  }

  @Test
  void neverRetypesADecimalReachableOnlyFromARequest() {
    OpenAPI api = requestOnlyApi("Money", numeric("price", "fee"));

    customize(api, Money.class);

    assertThat(typeOf(api, "Money", "price")).containsExactly("number");
  }

  /** Nullability is a separate axis: {@code null} rides through, and is never added. */
  @Test
  void carriesNullThroughWithoutTouchingTheNullabilityAxis() {
    Schema<?> component =
        new Schema<>()
            .addProperty("price", new Schema<>().types(ordered("number", "null")))
            .addProperty("fee", new Schema<>().type("number"));
    OpenAPI api = responseOnlyApi("Money", component);

    customize(api, Money.class);

    assertThat(typeOf(api, "Money", "price"))
        .as("the encoded-lie shape becomes honest, keeping null")
        .containsExactlyInAnyOrder("string", "null");
    assertThat(typeOf(api, "Money", "fee"))
        .as("a non-nullable decimal must not GAIN null")
        .containsExactly("string");
  }

  /** A decimal an author already annotated by hand is untouched — the sweep and the rule agree. */
  @Test
  void leavesAnAlreadyAnnotatedStringDecimalExactlyAsItIs() {
    Schema<?> component =
        new Schema<>()
            .addProperty("price", new Schema<>().types(ordered("string", "null")))
            .addProperty("fee", new Schema<>().type("string"));
    OpenAPI api = responseOnlyApi("Money", component);

    customize(api, Money.class);

    assertThat(typeOf(api, "Money", "price")).containsExactlyInAnyOrder("string", "null");
    assertThat(typeOf(api, "Money", "fee")).containsExactly("string");
  }

  /**
   * The case per-field annotation cannot express: a {@code List<BigDecimal>} has one schema for the
   * ARRAY and another for its items, and {@code @Schema} on the component lands on the array.
   */
  @Test
  void retypesTheItemsOfADecimalArrayNotTheArrayItself() {
    Schema<?> component =
        new Schema<>()
            .addProperty(
                "strikes", new Schema<>().type("array").items(new Schema<>().type("number")));
    OpenAPI api = responseOnlyApi("Strikes", component);

    customize(api, Strikes.class);

    Schema<?> strikes = property(api, "Strikes", "strikes");
    assertThat(strikes.getType()).isEqualTo("array");
    assertThat(strikes.getItems().getType()).isEqualTo("string");
  }

  /**
   * A genuine {@code Double} on a RESPONSE must survive — {@code ToStringSerializer} is registered
   * for {@link BigDecimal} only. This is what rules out the simpler "retype every response number"
   * shortcut; {@code KiteStatus.rateBudget} is exactly this shape in the committed edge-gateway spec.
   */
  @Test
  void leavesNonDecimalNumbersAloneBecauseOnlyBigDecimalSerializesAsAString() {
    Schema<?> component =
        new Schema<>()
            .addProperty("amount", new Schema<>().type("number"))
            .addProperty("ratio", new Schema<>().type("number").format("double"))
            .addProperty("count", new Schema<>().type("integer").format("int64"));
    OpenAPI api = responseOnlyApi("Mixed", component);

    customize(api, Mixed.class);

    assertThat(typeOf(api, "Mixed", "amount")).containsExactly("string");
    assertThat(property(api, "Mixed", "ratio").getType()).isEqualTo("number");
    assertThat(property(api, "Mixed", "ratio").getFormat()).isEqualTo("double");
    assertThat(property(api, "Mixed", "count").getType()).isEqualTo("integer");
  }

  /** A retyped decimal must not keep a numeric {@code format} that a string cannot have. */
  @Test
  void dropsTheNumericFormatWhenItRetypes() {
    Schema<?> component =
        new Schema<>().addProperty("price", new Schema<>().type("number").format("double"));
    OpenAPI api = responseOnlyApi("Money", component);

    customize(api, Money.class);

    assertThat(property(api, "Money", "price").getFormat()).isNull();
  }

  @Test
  void isIdempotentSoARepeatedCustomisePassIsStable() {
    OpenAPI api = responseOnlyApi("Money", numeric("price", "fee"));
    BigDecimalStringModelConverter facts = observe(Money.class);
    BigDecimalStringCustomizer customizer = new BigDecimalStringCustomizer(facts);

    customizer.customise(api);
    customizer.customise(api);

    assertThat(typeOf(api, "Money", "price")).containsExactly("string");
  }

  // --- harness -------------------------------------------------------------

  /** Drives the observer over the real record, then applies the customizer. */
  private static void customize(OpenAPI api, Class<?> record) {
    new BigDecimalStringCustomizer(observe(record)).customise(api);
  }

  /** Runs the converter the way springdoc does: chain resolves the record to its {@code $ref}. */
  private static BigDecimalStringModelConverter observe(Class<?> record) {
    BigDecimalStringModelConverter converter = new BigDecimalStringModelConverter();
    converter.resolve(
        new AnnotatedType(record),
        null,
        List.<ModelConverter>of(new RefStub(record.getSimpleName())).iterator());
    return converter;
  }

  /** Stands in for swagger-core's resolver: a record resolves to a component reference. */
  private record RefStub(String name) implements ModelConverter {
    @Override
    public Schema<?> resolve(
        AnnotatedType type,
        io.swagger.v3.core.converter.ModelConverterContext context,
        java.util.Iterator<ModelConverter> chain) {
      return ref(name);
    }
  }

  private static Schema<?> ref(String name) {
    return new Schema<>().$ref("#/components/schemas/" + name);
  }

  private static Schema<?> numeric(String... properties) {
    Schema<?> component = new Schema<>();
    for (String property : properties) {
      component.addProperty(property, new Schema<>().type("number"));
    }
    return component;
  }

  /** {@code types} is a Set; order it so an assertion can read deterministically. */
  private static Set<String> ordered(String... types) {
    return new LinkedHashSet<>(List.of(types));
  }

  private static Schema<?> property(OpenAPI api, String component, String property) {
    return (Schema<?>) api.getComponents().getSchemas().get(component).getProperties().get(property);
  }

  /** The effective type set, however swagger happens to be carrying it. */
  private static Set<String> typeOf(OpenAPI api, String component, String property) {
    Schema<?> schema = property(api, component, property);
    Set<String> types = schema.getTypes();
    if (types != null && !types.isEmpty()) {
      return types;
    }
    return schema.getType() == null ? Collections.emptySet() : Set.of(schema.getType());
  }

  private static OpenAPI responseOnlyApi(String name, Schema<?> component) {
    OpenAPI api = baseApi(name, component);
    api.getPaths()
        .get("/x")
        .getGet()
        .responses(
            new ApiResponses()
                .addApiResponse(
                    "200",
                    new ApiResponse()
                        .content(new Content().addMediaType(CE, new MediaType().schema(ref(name))))));
    return api;
  }

  private static OpenAPI requestOnlyApi(String name, Schema<?> component) {
    OpenAPI api = baseApi(name, component);
    api.getPaths()
        .get("/x")
        .getGet()
        .setRequestBody(
            new RequestBody()
                .content(new Content().addMediaType(CE, new MediaType().schema(ref(name)))));
    return api;
  }

  private static OpenAPI baseApi(String name, Schema<?> component) {
    OpenAPI api = new OpenAPI();
    api.setComponents(new Components().addSchemas(name, component));
    api.setPaths(new Paths().addPathItem("/x", new PathItem().get(new Operation())));
    return api;
  }
}
