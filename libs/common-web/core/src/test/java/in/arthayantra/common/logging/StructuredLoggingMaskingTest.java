package in.arthayantra.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * End-to-end A.6.2 check: a Boot app with common-web-core on classpath logs structured ECS JSON to
 * stdout by default (no extra encoder dependency), and the masking customizer scrubs secrets from
 * the JSON output.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingMaskingTest {

  @Configuration
  static class EmptyApp {}

  @Test
  void ecsJsonConsoleWithMaskingIsTheDefault(CapturedOutput output) {
    SpringApplication app = new SpringApplication(EmptyApp.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    try (ConfigurableApplicationContext ignored = app.run()) {
      LoggerFactory.getLogger("masking.e2e").info("startup with api_secret=topsecret123 echo");
    }

    assertThat(output.getOut()).contains("\"ecs\":{\"version\"");
    assertThat(output.getOut()).doesNotContain("topsecret123");
    assertThat(output.getOut()).contains("api_secret=***");
  }
}
