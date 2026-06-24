package in.arthayantra.strategysignal.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * Live-profile wiring for the OpenAlgo order-READ gateway (§18.1, Phase-4b). The
 * {@link RestOpenAlgoOrderReadGateway} bean replaces the default {@link DisabledOrderGateway}
 * (selected by {@code @ConditionalOnMissingBean}) ONLY when {@code artha.openalgo.order-read-enabled
 * =true} on the {@code live} profile. Default OFF — a mock or Kite-only live stack keeps the
 * fail-safe disabled gateway (empty reads, {@code NOT_CONFIGURED} funds) and needs no OpenAlgo API
 * key. Reads only: this bean never enables the order-WRITE path.
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(OpenAlgoOrderProperties.class)
public class OpenAlgoOrderConfig {

  /** The OpenAlgo order-read gateway, gated on {@code artha.openalgo.order-read-enabled=true}. */
  @Bean
  @ConditionalOnProperty(name = "artha.openalgo.order-read-enabled", havingValue = "true")
  public OrderGateway openAlgoOrderReadGateway(
      RestClient.Builder restClientBuilder,
      OpenAlgoOrderProperties properties,
      ObjectMapper objectMapper) {
    return new RestOpenAlgoOrderReadGateway(
        restClientBuilder, properties.baseUrl(), properties.resolveApiKey(), objectMapper);
  }
}
