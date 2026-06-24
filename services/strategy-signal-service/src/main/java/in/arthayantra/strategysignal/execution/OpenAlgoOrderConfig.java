package in.arthayantra.strategysignal.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

/**
 * Live-profile wiring for the OpenAlgo order gateway. Exactly one {@link OrderGateway} bean is
 * selected, in precedence order:
 *
 * <ol>
 *   <li><b>WRITE</b> — {@link RestOpenAlgoOrderGateway} when {@code artha.scalper.execution=live}
 *       (master plan §17.3). The live order path: it places orders AND surfaces the §18.1 reads.
 *       <b>Default OFF</b> — the flag defaults to {@code paper}; the owner arms it deliberately
 *       behind the §17.3 latency gate, and {@link LiveOrderService} still only places on the owner's
 *       Take.
 *   <li><b>READ-only</b> — {@link RestOpenAlgoOrderReadGateway} when {@code execution} is NOT live
 *       but {@code artha.openalgo.order-read-enabled=true} (§18.1, Phase-4b). Declared with
 *       {@code @ConditionalOnMissingBean} so the write gateway, when present, takes precedence and
 *       no two {@link OrderGateway} beans ever co-exist. Reads only — never the write path.
 *   <li><b>DISABLED</b> — the fail-safe {@link DisabledOrderGateway} ({@code @ConditionalOnMissingBean})
 *       otherwise: a mock or Kite-only live stack places nothing and returns empty reads /
 *       {@code NOT_CONFIGURED} funds, needing no OpenAlgo API key.
 * </ol>
 *
 * <p>Both OpenAlgo beans need only OpenAlgo's OWN generated API key (never the fronted broker's
 * secret). This config is on the {@code live} profile only; a mock stack always gets the disabled
 * gateway.
 */
@Configuration
@Profile("live")
@EnableConfigurationProperties(OpenAlgoOrderProperties.class)
public class OpenAlgoOrderConfig {

  /**
   * The OpenAlgo order-WRITE gateway — the live order path, gated on
   * {@code artha.scalper.execution=live} (default {@code paper} ⇒ this bean is absent and the
   * fail-safe disabled gateway is selected). Declared first so it wins precedence over the read-only
   * gateway. It also serves the §18.1 reads (delegated), so a live write stack is a complete gateway.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.scalper.execution", havingValue = "live")
  public OrderGateway openAlgoOrderGateway(
      RestClient.Builder restClientBuilder,
      OpenAlgoOrderProperties properties,
      ObjectMapper objectMapper) {
    return new RestOpenAlgoOrderGateway(
        restClientBuilder,
        properties.baseUrl(),
        properties.resolveApiKey(),
        properties.strategy(),
        objectMapper);
  }

  /**
   * The OpenAlgo order-READ gateway, gated on {@code artha.openalgo.order-read-enabled=true} and
   * selected only when no write gateway is present ({@code @ConditionalOnMissingBean}) — so reads
   * never enable the write path and the two never collide.
   */
  @Bean
  @ConditionalOnProperty(name = "artha.openalgo.order-read-enabled", havingValue = "true")
  @ConditionalOnMissingBean(OrderGateway.class)
  public OrderGateway openAlgoOrderReadGateway(
      RestClient.Builder restClientBuilder,
      OpenAlgoOrderProperties properties,
      ObjectMapper objectMapper) {
    return new RestOpenAlgoOrderReadGateway(
        restClientBuilder, properties.baseUrl(), properties.resolveApiKey(), objectMapper);
  }
}
