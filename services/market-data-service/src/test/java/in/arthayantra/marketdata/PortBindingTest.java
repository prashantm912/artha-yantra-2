package in.arthayantra.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.kite.HistoricalCandleGateway;
import in.arthayantra.marketdata.kite.InstrumentDumpGateway;
import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.QuoteGateway;
import in.arthayantra.marketdata.kite.SessionGateway;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The Phase-7 profile rule fixing the v1 startup trap: exactly one impl per port is always bound
 * — mock xor live — and live without credentials fails fast at startup (COMMON §10.3).
 */
class PortBindingTest {

  @Nested
  @SpringBootTest(
      properties = {
        "spring.profiles.active=mock",
        "artha.feed.autostart=false" // no Redis in this context
      })
  class MockProfile {

    @Autowired private ApplicationContext context;

    @Test
    void exactlyOneImplementationPerPort() {
      assertThat(context.getBeanNamesForType(SessionGateway.class)).hasSize(1);
      assertThat(context.getBeanNamesForType(MarketFeed.class)).hasSize(1);
      assertThat(context.getBeanNamesForType(QuoteGateway.class)).hasSize(1);
      assertThat(context.getBeanNamesForType(HistoricalCandleGateway.class)).hasSize(1);
      assertThat(context.getBeanNamesForType(InstrumentDumpGateway.class)).hasSize(1);
    }

    @Test
    void mockSessionIsAlwaysValid() {
      assertThat(context.getBean(SessionGateway.class).sessionActive()).isTrue();
      assertThat(context.getBean(SessionGateway.class).statusLabel()).isEqualTo("MOCK");
    }
  }

  @Nested
  class LiveProfile {

    @Test
    void liveWithoutCredentialsFailsFastAtStartup() {
      SpringApplication app = new SpringApplication(MarketDataServiceApplication.class);
      app.setWebApplicationType(WebApplicationType.NONE);
      app.setAdditionalProfiles("live");
      app.setDefaultProperties(
          java.util.Map.of(
              "artha.kite.api-key-file", "Z:/definitely/absent/kite_api_key",
              "artha.kite.api-secret-file", "Z:/definitely/absent/kite_api_secret",
              "artha.feed.autostart", "false"));

      assertThatThrownBy(app::run)
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("live profile requires Kite credentials");
    }
  }
}
