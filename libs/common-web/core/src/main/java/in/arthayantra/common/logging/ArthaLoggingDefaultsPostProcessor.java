package in.arthayantra.common.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The shared JSON-logging defaults every Java service imports (A.6.2): structured ECS console
 * output via Boot 3.5's native formatter (no extra encoder dependency) with the masking customizer
 * wired in. Added at lowest precedence so a service profile (e.g. {@code dev}) can override to
 * human-readable logs.
 */
public class ArthaLoggingDefaultsPostProcessor implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    Map<String, Object> defaults = new LinkedHashMap<>();
    defaults.put("logging.structured.format.console", "ecs");
    defaults.put(
        "logging.structured.json.customizer",
        "in.arthayantra.common.logging.ArthaMaskingJsonCustomizer");
    environment.getPropertySources().addLast(new MapPropertySource("artha-logging-defaults", defaults));
  }
}
