package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * The safety assertion that matters most: with NOTHING set — i.e. exactly what the shipped
 * {@code application.yml} gives a live deployment — the drill injector does not exist and its
 * endpoint is not served. {@link SignalFaultInjectionGuardTest} covers the condition matrix; this
 * one pins the real, default application context so a stray property default or an accidental
 * {@code matchIfMissing = true} can never ship unnoticed.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
@AutoConfigureMockMvc
class SignalFaultInjectionDisabledByDefaultIntegrationTest extends StrategySignalIntegrationTestBase {

  /** Boots without market-data on the wire (same shape as the contract-capture context). */
  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    in.arthayantra.strategysignal.registry.MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> true;
    }
  }

  @Autowired private ApplicationContext context;
  @Autowired private MockMvc mockMvc;

  @Test
  void byDefaultTheInjectorBeanDoesNotExist() {
    assertThat(context.getBeanNamesForType(SignalFaultInjector.class))
        .as("fault injection must be OFF unless artha.signals.fault-injection.enabled=true")
        .isEmpty();
    assertThat(context.getBeanNamesForType(SignalFaultInjectionController.class))
        .as("the drill endpoint must not be mapped by default")
        .isEmpty();
  }

  /** The endpoint is not merely inert — it is not routed at all. */
  @Test
  void byDefaultTheDrillEndpointIsNotServed() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.post("/api/v1/signal-fault-injection/subscription-stall"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
