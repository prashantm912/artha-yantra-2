package in.arthayantra.common.web.openapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records, per generated schema name, which components of a Java {@code record} Jackson is
 * guaranteed to emit as a key — the raw material {@link ResponseRequiredCustomizer} turns into an
 * OpenAPI {@code required} array.
 *
 * <p>Why this exists: springdoc emits no {@code required} for record-backed schemas, so every
 * property is optional — and openapi-diff has no "response property removed" rule at all (only
 * {@code incompatible.response.required.decreased}). A removed or RENAMED optional response key is
 * therefore "backward compatible" by construction, which is precisely the break the contract gate
 * exists to catch (consumers read these keys over the wire with no compile-time link).
 *
 * <p>The rule is Jackson's emission behaviour, not Java nullability: OpenAPI {@code required} means
 * "the key is PRESENT", not "the value is non-null". A record component is always emitted unless
 * {@code @JsonInclude} (on the component or its declaring record) suppresses it. This platform sets
 * no global {@code default-property-inclusion}, so Jackson's {@code ALWAYS} default applies.
 *
 * <p>This converter only OBSERVES: it never mutates a schema. Applying {@code required} is
 * position-sensitive (safe for responses, a false contract for request bodies) and position is a
 * document-level property, so the decision lives in the customizer.
 */
public class RecordRequiredModelConverter implements ModelConverter {

  private static final String REF_PREFIX = "#/components/schemas/";

  private final Map<String, Set<String>> alwaysEmitted = new ConcurrentHashMap<>();

  /** Schema name to the property names Jackson always emits for it. */
  public Map<String, Set<String>> alwaysEmittedProperties() {
    return Map.copyOf(alwaysEmitted);
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
      alwaysEmitted.put(name, alwaysEmittedOf(raw));
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

  private static Set<String> alwaysEmittedOf(Class<?> record) {
    JsonInclude classLevel = record.getAnnotation(JsonInclude.class);
    Set<String> names = new TreeSet<>();
    for (RecordComponent component : record.getRecordComponents()) {
      if (alwaysEmits(record, component, classLevel)) {
        names.add(propertyName(record, component));
      }
    }
    return Set.copyOf(names);
  }

  private static boolean alwaysEmits(
      Class<?> record, RecordComponent component, JsonInclude classLevel) {
    JsonInclude effective = annotation(record, component, JsonInclude.class);
    if (effective == null) {
      effective = classLevel;
    }
    if (effective == null) {
      return true; // Jackson's default inclusion is ALWAYS — the key is always written.
    }
    return effective.value() == JsonInclude.Include.ALWAYS
        || effective.value() == JsonInclude.Include.USE_DEFAULTS;
  }

  private static String propertyName(Class<?> record, RecordComponent component) {
    JsonProperty named = annotation(record, component, JsonProperty.class);
    if (named != null && !named.value().isEmpty()) {
      return named.value();
    }
    return component.getName();
  }

  /**
   * Reads an annotation written on a record component. Jackson's annotations do not target
   * {@code RECORD_COMPONENT}, so javac propagates them to the backing field / accessor and
   * {@link RecordComponent#getAnnotation} alone returns null — all three sites must be consulted.
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
