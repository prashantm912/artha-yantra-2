package in.arthayantra.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.gateway.auth.OwnerAuthService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CD-8 contract capture for the gateway's OWN surface (auth/session/system). Path lint allows
 * /api/v1 only — gateway-routed paths belong to their owning services' specs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ContractCaptureTest {

  @Container @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void ownerHash(DynamicPropertyRegistry registry) {
    registry.add("artha.owner-password-hash", () -> OwnerAuthService.ENCODER.encode("capture"));
  }

  @Autowired private WebTestClient client;

  @Test
  void specCapturesCleanAndHonorsTheD8Conventions() throws Exception {
    // the docs surface sits behind the owner session like everything else
    org.springframework.http.ResponseCookie session =
        java.util.Objects.requireNonNull(
            client
                .post()
                .uri("/api/v1/auth/login")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("password=capture")
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst("SESSION"));
    byte[] raw =
        client
            .get()
            .uri("/v3/api-docs")
            .cookie("SESSION", session.getValue())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode spec = objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
    ((ObjectNode) spec).remove("servers");

    spec.path("paths")
        .fieldNames()
        .forEachRemaining(path -> assertThat(path).startsWith("/api/v1/"));
    assertThat(spec.path("components").path("schemas").has("ErrorResponse")).isTrue();

    String pretty =
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";
    Files.createDirectories(Path.of("target/contracts"));
    Files.writeString(Path.of("target/contracts/edge-gateway.openapi.json"), pretty);
    if (Boolean.getBoolean("contracts.capture")) {
      Files.writeString(repoRoot().resolve("contracts/edge-gateway.openapi.json"), pretty);
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
