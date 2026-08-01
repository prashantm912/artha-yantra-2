package in.arthayantra.common.web.openapi;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects two DISTINCT Java types resolving into ONE schema component name — the silent collapse
 * behind chip task_1c04803f: springdoc keys components by SIMPLE class name, so twins with
 * different field sets collapse to one spec schema on scan order and the loser's fields vanish
 * from the contract (worst instance: {@code POST /api/v1/auth/kite/session} published three
 * bhavcopy int counters and none of its own {@code ExchangeResult} fields, PR #1012).
 *
 * <p>#1012 renamed the then-live collisions via {@code @Schema(name = ...)}; nothing PREVENTED the
 * next pair of same-named records from collapsing again — a field-identical twin pair is invisible
 * even to a careful spec read, and field-identical twins drift (the sweep doc had called
 * {@code ExchangeResult} field-identical; the twins shared ZERO fields). This converter OBSERVES
 * every resolution and records, per resolved component name, the canonical Java types that
 * produced it; each service's {@code ContractCaptureTest} asserts {@link #collisions()} is empty,
 * so a new collision fails that service's CI shard with both fully-qualified names in the message.
 *
 * <p>Wrapper types that DELEGATE their resolution (e.g. {@code Optional<Row>} resolves to Row's
 * own component) must not be mistaken for a second producer of the payload's name, so a type is
 * recorded only when the component name is plausibly its OWN: the name equals the class's
 * {@code @Schema(name = ...)} override, or equals / starts with its simple name (the prefix form
 * is swagger's naming for parameterized types, e.g. {@code Page<Foo>} → {@code PageFoo}). The one
 * blind spot that guard buys: a wrapper whose simple name is itself a prefix of its payload's
 * component name would be skipped — no such pair exists in this repo.
 *
 * <p>Like {@link RecordRequiredModelConverter}, this only observes; it never mutates a schema.
 */
public class SchemaNameCollisionDetector implements ModelConverter {

  private static final Logger log = LoggerFactory.getLogger(SchemaNameCollisionDetector.class);
  private static final String REF_PREFIX = "#/components/schemas/";

  private final Map<String, Set<String>> typesBySchemaName = new ConcurrentHashMap<>();

  /**
   * Component names produced by more than one distinct Java type, each with every canonical type
   * that resolved to it. Empty means no collapse; sorted so the assertion message is stable.
   */
  public Map<String, Set<String>> collisions() {
    Map<String, Set<String>> out = new TreeMap<>();
    typesBySchemaName.forEach(
        (name, types) -> {
          if (types.size() > 1) {
            out.put(name, Set.copyOf(new TreeSet<>(types)));
          }
        });
    return Map.copyOf(out);
  }

  @Override
  public Schema resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    Schema<?> resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
    if (resolved == null) {
      return null;
    }
    com.fasterxml.jackson.databind.JavaType javaType = javaTypeOf(type);
    if (javaType == null) {
      return resolved;
    }
    String name = schemaName(resolved);
    if (name != null && ownsName(javaType.getRawClass(), name)) {
      Set<String> producers =
          typesBySchemaName.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet());
      if (producers.add(javaType.toCanonical()) && producers.size() > 1) {
        log.warn(
            "schema component '{}' is produced by {} distinct Java types {} — all but one silently "
                + "vanish from the contract; disambiguate with @Schema(name = ...)",
            name, producers.size(), producers);
      }
    }
    return resolved;
  }

  private static com.fasterxml.jackson.databind.JavaType javaTypeOf(AnnotatedType type) {
    if (type == null || type.getType() == null) {
      return null;
    }
    try {
      return TypeFactory.defaultInstance().constructType(type.getType());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A resolved component is normally a {@code $ref}; fall back to the inline schema name. */
  private static String schemaName(Schema<?> resolved) {
    String ref = resolved.get$ref();
    if (ref != null && ref.startsWith(REF_PREFIX)) {
      return ref.substring(REF_PREFIX.length());
    }
    return resolved.getName();
  }

  /**
   * Whether the component name is plausibly this class's OWN rather than a delegated payload's —
   * the explicit {@code @Schema(name = ...)} override, the simple name, or the simple name as a
   * prefix (swagger's parameterized-type naming).
   */
  private static boolean ownsName(Class<?> raw, String name) {
    io.swagger.v3.oas.annotations.media.Schema declared =
        raw.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
    if (declared != null && !declared.name().isEmpty()) {
      return name.equals(declared.name());
    }
    return name.startsWith(raw.getSimpleName());
  }
}
