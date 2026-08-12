package in.arthayantra.common.web.openapi;

import io.swagger.v3.core.converter.ModelConverter;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Wires the record-{@code required} pair into every service's springdoc pipeline (springdoc picks
 * {@link ModelConverter} beans up automatically). Conditional on springdoc being present, since
 * common-web-core is also consumed without it.
 */
@AutoConfiguration
@ConditionalOnClass({ModelConverter.class, GlobalOpenApiCustomizer.class})
public class ArthaOpenApiAutoConfiguration {

  /** Observes record types as springdoc resolves them. */
  @Bean
  public RecordRequiredModelConverter recordRequiredModelConverter() {
    return new RecordRequiredModelConverter();
  }

  /** Observes schema-name collisions (task_1c04803f); asserted empty by every ContractCaptureTest. */
  @Bean
  public SchemaNameCollisionDetector schemaNameCollisionDetector() {
    return new SchemaNameCollisionDetector();
  }

  /** Observes which record components are {@link java.math.BigDecimal}-backed. */
  @Bean
  public BigDecimalStringModelConverter bigDecimalStringModelConverter() {
    return new BigDecimalStringModelConverter();
  }

  /** Retypes response-side decimals to {@code string}, matching {@code ToStringSerializer}. */
  @Bean
  public BigDecimalStringCustomizer bigDecimalStringCustomizer(
      BigDecimalStringModelConverter bigDecimalStringModelConverter) {
    return new BigDecimalStringCustomizer(bigDecimalStringModelConverter);
  }

  /** Rewrites nullable record references into an honest OpenAPI 3.1 {@code anyOf}. */
  @Bean
  public NullableRefCustomizer nullableRefCustomizer() {
    return new NullableRefCustomizer();
  }

  /** Applies {@code required} to response-only schemas once the document is assembled. */
  @Bean
  public ResponseRequiredCustomizer responseRequiredCustomizer(
      RecordRequiredModelConverter recordRequiredModelConverter) {
    return new ResponseRequiredCustomizer(recordRequiredModelConverter);
  }
}
