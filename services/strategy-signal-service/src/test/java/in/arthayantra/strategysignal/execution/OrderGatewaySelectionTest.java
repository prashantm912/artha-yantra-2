package in.arthayantra.strategysignal.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Flag-gating seam: exactly ONE {@link OrderGateway} bean is selected, and the order-WRITE path is
 * inert unless deliberately armed. Pins the SAME conditions production uses (the real
 * {@link OpenAlgoOrderConfig} write/read beans + the {@link DisabledOrderGateway} fail-safe under
 * {@code @ConditionalOnMissingBean}) without booting the live stack:
 *
 * <ul>
 *   <li>default (no {@code artha.scalper.execution}) ⇒ {@link DisabledOrderGateway} — places nothing;
 *   <li>{@code execution=paper} (the default mode) ⇒ {@link DisabledOrderGateway};
 *   <li>{@code execution=live} ⇒ {@link RestOpenAlgoOrderGateway} — the live order path;
 *   <li>{@code order-read-enabled=true} alone (execution NOT live) ⇒ the READ-only gateway, never the
 *       write path.
 * </ul>
 */
class OrderGatewaySelectionTest {

  // OpenAlgoOrderConfig is @Profile("live") — activate it so the seam exercises the real live wiring.
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("live"))
          .withUserConfiguration(Deps.class, OpenAlgoOrderConfig.class, Fallback.class);

  @Test
  void defaultExecutionBindsTheDisabledGatewayThatPlacesNothing() {
    runner.run(
        ctx -> {
          assertThat(ctx.getBeanNamesForType(OrderGateway.class)).hasSize(1);
          assertThat(ctx.getBean(OrderGateway.class)).isInstanceOf(DisabledOrderGateway.class);
        });
  }

  @Test
  void paperExecutionBindsTheDisabledGateway() {
    runner
        .withPropertyValues("artha.scalper.execution=paper")
        .run(
            ctx ->
                assertThat(ctx.getBean(OrderGateway.class))
                    .isInstanceOf(DisabledOrderGateway.class));
  }

  @Test
  void liveExecutionBindsTheOpenAlgoWriteGateway() {
    runner
        .withPropertyValues(
            "artha.scalper.execution=live", "artha.openalgo.api-key=test-key")
        .run(
            ctx -> {
              assertThat(ctx.getBeanNamesForType(OrderGateway.class)).hasSize(1);
              assertThat(ctx.getBean(OrderGateway.class))
                  .isInstanceOf(RestOpenAlgoOrderGateway.class);
            });
  }

  @Test
  void readEnabledAloneBindsTheReadOnlyGatewayNotTheWritePath() {
    runner
        .withPropertyValues(
            "artha.openalgo.order-read-enabled=true", "artha.openalgo.api-key=test-key")
        .run(
            ctx -> {
              assertThat(ctx.getBeanNamesForType(OrderGateway.class)).hasSize(1);
              assertThat(ctx.getBean(OrderGateway.class))
                  .isInstanceOf(RestOpenAlgoOrderReadGateway.class);
            });
  }

  /** The dependencies the OpenAlgo beans need, without booting the live stack. */
  @Configuration
  @EnableConfigurationProperties(OpenAlgoOrderProperties.class)
  static class Deps {
    @Bean
    RestClient.Builder restClientBuilder() {
      return RestClient.builder();
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  /** The fail-safe fallback, mirroring the production {@code @ConditionalOnMissingBean} component. */
  @Configuration
  static class Fallback {
    @Bean
    @ConditionalOnMissingBean(value = OrderGateway.class, ignored = DisabledOrderGateway.class)
    DisabledOrderGateway disabledOrderGateway() {
      return new DisabledOrderGateway();
    }
  }
}
