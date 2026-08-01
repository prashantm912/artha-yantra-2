package in.arthayantra.common.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;

/**
 * {@link SchemaNameCollisionDetector} — the task_1c04803f prevention half.
 *
 * <p>springdoc keys components by SIMPLE class name, so two distinct records named {@code Row}
 * collapse to ONE spec schema on scan order and the loser's fields vanish from the contract. #1012
 * fixed the then-live collisions by renaming; this detector is what makes the NEXT one fail a
 * ContractCaptureTest instead of shipping silently.
 */
class SchemaNameCollisionDetectorTest {

  /** First twin — same simple name as {@link Second.Row}, different fields. */
  static final class First {
    record Row(String alpha) {}
  }

  /** Second twin. */
  static final class Second {
    record Row(int beta) {}
  }

  /** The sanctioned fix: an explicit component name clears the collision. */
  static final class Renamed {
    @Schema(name = "RenamedRow")
    record Row(int beta) {}
  }

  @Test
  void twinRecordsWithTheSameSimpleNameAreReportedAsOneCollision() {
    SchemaNameCollisionDetector detector = new SchemaNameCollisionDetector();
    ModelConverters converters = converters(detector);

    converters.readAllAsResolvedSchema(new AnnotatedType(First.Row.class));
    converters.readAllAsResolvedSchema(new AnnotatedType(Second.Row.class));

    assertThat(detector.collisions()).containsOnlyKeys("Row");
    assertThat(detector.collisions().get("Row"))
        .as("both producers named, so the failure message identifies the twins")
        .containsExactlyInAnyOrder(First.Row.class.getName(), Second.Row.class.getName());
  }

  @Test
  void aSchemaNameRenameClearsTheCollision() {
    SchemaNameCollisionDetector detector = new SchemaNameCollisionDetector();
    ModelConverters converters = converters(detector);

    converters.readAllAsResolvedSchema(new AnnotatedType(First.Row.class));
    io.swagger.v3.core.converter.ResolvedSchema renamed =
        converters.readAllAsResolvedSchema(new AnnotatedType(Renamed.Row.class));

    // guard against a vacuous pass: the rename must have actually taken effect in swagger
    assertThat(renamed.schema.getName()).isEqualTo("RenamedRow");
    assertThat(detector.collisions())
        .as("@Schema(name=) is the sanctioned disambiguation — no collision left")
        .isEmpty();
  }

  @Test
  void resolvingTheSameTypeRepeatedlyReportsNoCollision() {
    SchemaNameCollisionDetector detector = new SchemaNameCollisionDetector();
    ModelConverters converters = converters(detector);

    converters.readAllAsResolvedSchema(new AnnotatedType(First.Row.class));
    converters.readAllAsResolvedSchema(new AnnotatedType(First.Row.class));

    assertThat(detector.collisions()).isEmpty();
  }

  @Test
  void optionalWrapperDelegatingToItsPayloadDoesNotProduceTheName() throws Exception {
    SchemaNameCollisionDetector detector = new SchemaNameCollisionDetector();
    ModelConverters converters = converters(detector);

    converters.readAllAsResolvedSchema(new AnnotatedType(First.Row.class));
    io.swagger.v3.core.converter.ResolvedSchema viaWrapper =
        converters.readAllAsResolvedSchema(
            new AnnotatedType(
                Holder.class.getDeclaredMethod("maybe").getGenericReturnType()));

    // guard against a vacuous pass: the wrapper resolution must actually have reached Row
    assertThat(viaWrapper.referencedSchemas).containsKey("Row");
    assertThat(detector.collisions())
        .as("Optional<Row> resolves to Row's own component; it does not produce the name")
        .isEmpty();
  }

  /** Supplies a real {@code Optional<First.Row>} generic type token. */
  static final class Holder {
    java.util.Optional<First.Row> maybe() {
      return java.util.Optional.empty();
    }
  }

  /** OpenAPI 3.1 resolution, mirroring the springdoc pipeline the services run. */
  private static ModelConverters converters(SchemaNameCollisionDetector detector) {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(detector);
    return converters;
  }
}
