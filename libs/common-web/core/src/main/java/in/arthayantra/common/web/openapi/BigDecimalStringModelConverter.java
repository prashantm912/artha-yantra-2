package in.arthayantra.common.web.openapi;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records, per generated schema name, which properties of a Java {@code record} are backed by a
 * {@link BigDecimal} — the raw material {@link BigDecimalStringCustomizer} turns into an OpenAPI
 * {@code string} type on response schemas.
 *
 * <p>Why this exists: {@code ArthaJacksonAutoConfiguration} registers {@code ToStringSerializer} for
 * {@link BigDecimal} platform-wide, so every decimal is a JSON <em>string</em> on the wire while
 * springdoc infers {@code number}. That gap was closed field-by-field with ~484 {@code @Schema(type =
 * "string")} annotations; stating the rule once here means a decimal added tomorrow is honest by
 * construction rather than by an author remembering the annotation.
 *
 * <p>This converter only OBSERVES: it never mutates a schema, for the same reason {@link
 * RecordRequiredModelConverter} does not. A {@link ModelConverter} resolves a type without knowing
 * where the type ends up, and the number/string question is <b>position-sensitive</b>:
 *
 * <ul>
 *   <li><b>Responses</b> — the serializer always writes a string, so {@code number} is a lie.
 *   <li><b>Requests</b> — nothing overrides Jackson's stock {@code BigDecimal} deserializer, which
 *       accepts a JSON number <em>and</em> a JSON string. {@code number} is TRUE there, and retyping
 *       it to {@code string} would publish a new lie in the other direction: it tightens the
 *       generated TS client and tells callers a numeric literal is invalid when the server takes it.
 * </ul>
 *
 * <p>Measured on the committed specs at {@code bf93ebf7}: every one of the 28 surviving {@code type:
 * number} nodes is a request surface — 15 request-body properties ({@code BacktestRunRequest},
 * {@code ExitKnobs}, {@code OrderBody}, …) and 13 {@code @RequestParam BigDecimal} query parameters.
 * A position-blind converter would have retyped all 28. Position is a document-level property, so
 * the decision lives in the customizer.
 */
public class BigDecimalStringModelConverter implements ModelConverter {

  private static final String REF_PREFIX = "#/components/schemas/";

  /** Where a {@link BigDecimal} sits relative to the property's schema. */
  public enum Decimal {
    /** The property itself is a decimal. */
    SCALAR,
    /** The property is an array whose ITEMS are decimals. */
    ELEMENT
  }

  private final Map<String, Map<String, Decimal>> decimals = new ConcurrentHashMap<>();

  /** Schema name to the properties that are {@link BigDecimal}-backed, and how. */
  public Map<String, Map<String, Decimal>> decimalProperties() {
    return Map.copyOf(decimals);
  }

  @Override
  public Schema resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    if (resolved == null) {
      return null;
    }
    Class<?> raw = rawClass(type);
    if (raw == null || !raw.isRecord()) {
      return resolved;
    }
    String name = schemaName(resolved);
    if (name != null) {
      Map<String, Decimal> found = decimalsOf(raw);
      if (!found.isEmpty()) {
        decimals.put(name, found);
      }
    }
    return resolved;
  }

  private static Class<?> rawClass(AnnotatedType type) {
    if (type == null || type.getType() == null) {
      return null;
    }
    try {
      return TypeFactory.defaultInstance().constructType(type.getType()).getRawClass();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A resolved record is normally a {@code $ref}; fall back to the inline schema name. */
  private static String schemaName(Schema<?> resolved) {
    String ref = resolved.get$ref();
    if (ref != null && ref.startsWith(REF_PREFIX)) {
      return ref.substring(REF_PREFIX.length());
    }
    return resolved.getName();
  }

  private static Map<String, Decimal> decimalsOf(Class<?> record) {
    Map<String, Decimal> found = new TreeMap<>();
    for (RecordComponent component : record.getRecordComponents()) {
      Decimal kind = kindOf(component);
      if (kind != null) {
        found.put(propertyName(record, component), kind);
      }
    }
    return Map.copyOf(found);
  }

  /** {@code BigDecimal} scalar, or a collection / array whose element type is {@code BigDecimal}. */
  private static Decimal kindOf(RecordComponent component) {
    Class<?> declared = component.getType();
    if (declared == BigDecimal.class) {
      return Decimal.SCALAR;
    }
    if (declared.isArray() && declared.getComponentType() == BigDecimal.class) {
      return Decimal.ELEMENT;
    }
    if (Collection.class.isAssignableFrom(declared)
        && component.getGenericType() instanceof ParameterizedType parameterized) {
      Type[] arguments = parameterized.getActualTypeArguments();
      if (arguments.length == 1 && arguments[0] == BigDecimal.class) {
        return Decimal.ELEMENT;
      }
    }
    return null;
  }

  private static String propertyName(Class<?> record, RecordComponent component) {
    JsonProperty named = annotation(record, component, JsonProperty.class);
    if (named != null && !named.value().isEmpty()) {
      return named.value();
    }
    return component.getName();
  }

  /**
   * Reads an annotation written on a record component. Jackson's annotations do not target {@code
   * RECORD_COMPONENT}, so javac propagates them to the backing field / accessor and {@link
   * RecordComponent#getAnnotation} alone returns null — all three sites must be consulted.
   */
  private static <A extends Annotation> A annotation(
      Class<?> record, RecordComponent component, Class<A> type) {
    A found = component.getAnnotation(type);
    if (found != null) {
      return found;
    }
    try {
      found = record.getDeclaredField(component.getName()).getAnnotation(type);
      if (found != null) {
        return found;
      }
    } catch (NoSuchFieldException ignored) {
      // A record always has a field per component; tolerate an exotic compiler anyway.
    }
    return component.getAccessor().getAnnotation(type);
  }
}
