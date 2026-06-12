package in.arthayantra.common.web.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Platform Jackson conventions (A.3): money = {@link BigDecimal} serialized as <em>strings</em>
 * (never floats), {@link OffsetDateTime} serialized with the {@code +05:30} offset. Runs as a
 * builder customizer so it deterministically overrides Boot's JavaTime defaults in both servlet
 * services and the WebFlux gateway.
 */
@AutoConfiguration
@ConditionalOnClass({ObjectMapper.class, Jackson2ObjectMapperBuilder.class})
public class ArthaJacksonAutoConfiguration {

  /** Applies the BigDecimal-as-string and IST-offset serializers. */
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer arthaJacksonCustomizer() {
    return builder ->
        builder
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializerByType(BigDecimal.class, ToStringSerializer.instance)
            .serializerByType(OffsetDateTime.class, new IstOffsetDateTimeSerializer());
  }
}
