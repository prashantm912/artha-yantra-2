package in.arthayantra.marketdata.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Reads the database password from the Docker secret file named by {@code artha.db.password-file}
 * into {@code spring.datasource.password} (A.9: {@code POSTGRES_PASSWORD} is never a plain env
 * var). Silently no-ops when the file is absent — tests override the datasource entirely via
 * Testcontainers {@code @ServiceConnection}.
 */
public class SecretFilePasswordPostProcessor implements EnvironmentPostProcessor, Ordered {

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    String fileProperty = environment.getProperty("artha.db.password-file");
    if (fileProperty == null || fileProperty.isBlank()) {
      return;
    }
    Path file = Path.of(fileProperty);
    if (!Files.isReadable(file)) {
      return;
    }
    try {
      String password = Files.readString(file, StandardCharsets.UTF_8).trim();
      if (!password.isEmpty()) {
        environment
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "artha-db-secret-file", Map.of("spring.datasource.password", password)));
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed reading database secret file " + file, e);
    }
  }

  @Override
  public int getOrder() {
    // after ConfigDataEnvironmentPostProcessor so yml-defined defaults are visible
    return Ordered.LOWEST_PRECEDENCE - 10;
  }
}
