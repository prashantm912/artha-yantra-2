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

  /** Applies {@code required} to response-only schemas once the document is assembled. */
  @Bean
  public ResponseRequiredCustomizer responseRequiredCustomizer(
      RecordRequiredModelConverter recordRequiredModelConverter) {
    return new ResponseRequiredCustomizer(recordRequiredModelConverter);
  }
}
