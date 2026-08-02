package in.arthayantra.common.web.openapi;

import in.arthayantra.common.web.openapi.BigDecimalStringModelConverter.Decimal;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.Ordered;

/**
 * Retypes {@link BigDecimal}-backed properties of RESPONSE schemas from {@code number} to {@code
 * string}, matching what {@code ToStringSerializer} actually writes on the wire.
 *
 * <p>Position matters, and it is the whole reason this runs on the assembled document rather than in
 * {@link BigDecimalStringModelConverter} (whose javadoc carries the measurement): a response decimal
 * is always a string, a request decimal is accepted as a number OR a string, and a {@link
 * io.swagger.v3.core.converter.ModelConverter} cannot tell the two apart. A schema reachable from
 * BOTH positions is left alone — correctness beats coverage, the same doctrine {@link
 * ResponseRequiredCustomizer} and {@link NullableRefCustomizer} already follow.
 *
 * <p><b>Nullability is a separate axis and is never touched.</b> Only numeric members of the type set
 * are rewritten; {@code null} is carried through untouched, and none is ever added. So an unannotated
 * decimal becomes bare {@code string} (springdoc does not infer nullability, and neither does this),
 * while a field already carrying {@code @Schema(type = "string", types = {"string", "null"})} keeps
 * its {@code ["string","null"]} exactly. Declaring nullability remains the author's job.
 *
 * <p>Runs before the other customizers so they see final types; it is order-independent in practice,
 * since neither touches a primitive numeric schema.
 */
public class BigDecimalStringCustomizer implements GlobalOpenApiCustomizer, Ordered {

  private static final String STRING = "string";

  /** Formats swagger emits for numeric types; meaningless once the type is a string. */
  private static final Set<String> NUMERIC_FORMATS =
      Set.of("float", "double", "int32", "int64");

  private final BigDecimalStringModelConverter facts;

  public BigDecimalStringCustomizer(BigDecimalStringModelConverter facts) {
    this.facts = facts;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 2;
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
    Map<String, Map<String, Decimal>> declared = facts.decimalProperties();
    if (declared.isEmpty()) {
      return;
    }
    for (String name : ResponseRequiredCustomizer.responseOnlyNames(openApi, schemas)) {
      Map<String, Decimal> properties = declared.get(name);
      Schema<?> schema = schemas.get(name);
      if (properties == null || schema == null || schema.getProperties() == null) {
        continue;
      }
      properties.forEach(
          (property, kind) -> retypeProperty(schema.getProperties().get(property), kind));
    }
  }

  private static void retypeProperty(Schema<?> property, Decimal kind) {
    if (property == null) {
      return;
    }
    retype(kind == Decimal.ELEMENT ? property.getItems() : property);
  }

  /**
   * Replaces every numeric member of a schema's type set with {@code string}, leaving {@code null}
   * and any other member untouched. A no-op when the schema is already a string — which is the case
   * for every decimal an author has annotated by hand.
   */
  private static void retype(Schema<?> schema) {
    if (schema == null) {
      return;
    }
    Set<String> types = schema.getTypes();
    if (types == null || types.isEmpty()) {
      if (isNumeric(schema.getType())) {
        schema.setType(STRING);
        clearNumericFormat(schema);
      }
      return;
    }
    if (types.stream().noneMatch(BigDecimalStringCustomizer::isNumeric)) {
      return;
    }
    Set<String> rewritten = new LinkedHashSet<>();
    for (String type : types) {
      rewritten.add(isNumeric(type) ? STRING : type);
    }
    schema.setTypes(rewritten);
    if (isNumeric(schema.getType())) {
      schema.setType(STRING);
    }
    clearNumericFormat(schema);
  }

  private static boolean isNumeric(String type) {
    return "number".equals(type) || "integer".equals(type);
  }

  private static void clearNumericFormat(Schema<?> schema) {
    String format = schema.getFormat();
    if (format != null && NUMERIC_FORMATS.contains(format)) {
      schema.setFormat(null);
    }
  }
}
