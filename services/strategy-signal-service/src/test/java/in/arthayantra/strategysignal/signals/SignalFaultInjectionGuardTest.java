package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The guard matrix for the drill injector: the bean and its endpoint must exist ONLY under an
 * explicit {@code artha.signals.fault-injection.enabled=true}. Absent and explicitly-false must both
 * yield NO bean — an injector that can be conjured by a missing property is not default-OFF.
 *
 * <p>{@link SignalFaultInjectionDisabledByDefaultIntegrationTest} proves the same thing against the
 * REAL application context and the shipped {@code application.yml}; this covers the condition matrix
 * exhaustively and cheaply.
 */
class SignalFaultInjectionGuardTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(SignalEngine.class, () -> mock(SignalEngine.class))
          .withBean(SubscriberHealthCanary.class, () -> mock(SubscriberHealthCanary.class))
          .withUserConfiguration(SignalFaultInjector.class, SignalFaultInjectionController.class);

  @Test
  void propertyAbsent_neitherBeanExists() {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(SignalFaultInjector.class);
          assertThat(context).doesNotHaveBean(SignalFaultInjectionController.class);
        });
  }

  @Test
  void propertyExplicitlyFalse_neitherBeanExists() {
    runner
        .withPropertyValues("artha.signals.fault-injection.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(SignalFaultInjector.class);
              assertThat(context).doesNotHaveBean(SignalFaultInjectionController.class);
            });
  }

  /** Discriminator: without this the two assertions above could pass for the wrong reason. */
  @Test
  void propertyTrue_bothBeansExist() {
    runner
        .withPropertyValues("artha.signals.fault-injection.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(SignalFaultInjector.class);
              assertThat(context).hasSingleBean(SignalFaultInjectionController.class);
            });
  }

  /**
   * The combination that used to break STARTUP rather than stay absent: injection explicitly on,
   * engine explicitly off. {@link SignalEngine} itself is conditional on {@code engine-enabled}, so
   * without mirroring that gate the injector would be created against a missing dependency and the
   * context would fail on an unsatisfied-dependency error. Absent beans is the correct outcome.
   */
  @Test
  void injectionEnabledButEngineDisabled_startsCleanlyWithNoBeans() {
    runner
        .withPropertyValues(
            "artha.signals.fault-injection.enabled=true", "artha.signals.engine-enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(SignalFaultInjector.class);
              assertThat(context).doesNotHaveBean(SignalFaultInjectionController.class);
            });
  }

  /** The engine gate must be default-ON, so an unset engine-enabled still allows a drill. */
  @Test
  void injectionEnabledAndEngineUnset_bothBeansExist() {
    runner
        .withPropertyValues("artha.signals.fault-injection.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(SignalFaultInjector.class));
  }
}
