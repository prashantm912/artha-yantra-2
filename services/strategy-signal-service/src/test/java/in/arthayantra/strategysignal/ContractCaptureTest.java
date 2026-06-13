package in.arthayantra.strategysignal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** CD-8 contract capture (Phase 24) — same shape as the market-data capture. */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
@AutoConfigureMockMvc
class ContractCaptureTest extends StrategySignalIntegrationTestBase {

  /** Boots without market-data on the wire. */
  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    in.arthayantra.strategysignal.registry.MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> true;
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void specCapturesCleanAndHonorsTheD8Conventions() throws Exception {
    String body =
        mockMvc
            .perform(MockMvcRequestBuilders.get("/v3/api-docs"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode spec = objectMapper.readTree(body);
    ((ObjectNode) spec).remove("servers");

    spec.path("paths")
        .fieldNames()
        .forEachRemaining(path -> assertThat(path).startsWith("/api/v1/"));
    assertThat(spec.path("components").path("schemas").has("ErrorResponse")).isTrue();
    spec.path("paths")
        .forEach(
            path ->
                path.forEach(
                    operation ->
                        assertThat(operation.path("responses").has("default"))
                            .as("every operation declares the default error envelope")
                            .isTrue()));

    String pretty =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";
    Files.createDirectories(Path.of("target/contracts"));
    Files.writeString(Path.of("target/contracts/strategy-signal-service.openapi.json"), pretty);
    if (Boolean.getBoolean("contracts.capture")) {
      Files.writeString(
          repoRoot().resolve("contracts/strategy-signal-service.openapi.json"), pretty);
    }
  }

  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null && !Files.isDirectory(dir.resolve("deploy/flyway"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException("repo root not found");
    }
    Path contracts = dir.resolve("contracts");
    try {
      Files.createDirectories(contracts);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return dir;
  }
}
