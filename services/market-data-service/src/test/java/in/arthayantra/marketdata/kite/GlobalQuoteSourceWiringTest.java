package in.arthayantra.marketdata.kite;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsConfig;
import in.arthayantra.marketdata.upstox.UpstoxGlobalQuoteClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Wiring invariants for the {@link GlobalQuoteSource} port's TWO implementations. Both consumers
 * ({@code ConnectingDotsService}, {@code MarketSurfaceController}) resolve the port through {@code
 * ObjectProvider.getIfAvailable()}, which throws on two candidates — so "at most one bean" is a
 * correctness property, not a style preference, and it is proven here against the REAL configuration
 * classes rather than a mirrored stub.
 *
 * <ul>
 *   <li>Default (both flags off) ⇒ NO bean — the Dow factor stays Neutral, byte-identical to today.
 *   <li>{@code artha.upstox.global-quotes-enabled=true} ⇒ exactly one bean, the Upstox client.
 *   <li>That flag WITHOUT {@code analytics.enabled} ⇒ loud startup failure, never a silent no-op.
 *   <li>BOTH global-quote flags on ⇒ {@link GlobalQuoteSourceExclusivityGuard} refuses the context.
 * </ul>
 */
class GlobalQuoteSourceWiringTest {

  /** The real Upstox config + the collaborators its beans construct with (no network at wiring time). */
  private final ApplicationContextRunner upstox =
      new ApplicationContextRunner()
          .withUserConfiguration(UpstoxAnalyticsConfig.class)
          .withBean(RestClient.Builder.class, RestClient::builder)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(MarketCalendar.class, MarketCalendar::nse)
          .withBean(Clock.class, Clock::systemUTC)
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues("spring.profiles.active=live");

  private final ApplicationContextRunner guard =
      new ApplicationContextRunner()
          .withUserConfiguration(GlobalQuoteSourceExclusivityGuard.class)
          .withPropertyValues("spring.profiles.active=live");

  @Test
  void defaultsBindNoGlobalQuoteSourceSoTheDowFactorStaysNeutral() {
    upstox
        .withPropertyValues("artha.upstox.analytics.enabled=true")
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(GlobalQuoteSource.class));
  }

  @Test
  void theUpstoxFlagBindsExactlyOneGlobalQuoteSource() {
    upstox
        .withPropertyValues(
            "artha.upstox.analytics.enabled=true", "artha.upstox.global-quotes-enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBeanNamesForType(GlobalQuoteSource.class)).hasSize(1);
              assertThat(ctx.getBean(GlobalQuoteSource.class))
                  .isInstanceOf(UpstoxGlobalQuoteClient.class);
            });
  }

  @Test
  void theUpstoxFlagWithoutTheAnalyticsTokenFailsLoudlyRatherThanSilently() {
    upstox
        .withPropertyValues("artha.upstox.global-quotes-enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("artha.upstox.analytics.enabled=true"));
  }

  @Test
  void armingBothGlobalQuoteBackendsRefusesTheContext() {
    guard
        .withPropertyValues(
            "artha.openalgo.global-quotes-enabled=true", "artha.upstox.global-quotes-enabled=true")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("mutually exclusive"));
  }

  @Test
  void eitherBackendAloneOrNeitherLeavesTheGuardInert() {
    guard.run(ctx -> assertThat(ctx).hasNotFailed());
    guard
        .withPropertyValues("artha.openalgo.global-quotes-enabled=true")
        .run(ctx -> assertThat(ctx).hasNotFailed());
    guard
        .withPropertyValues("artha.upstox.global-quotes-enabled=true")
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
