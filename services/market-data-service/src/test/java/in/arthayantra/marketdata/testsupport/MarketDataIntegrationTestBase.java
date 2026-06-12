package in.arthayantra.marketdata.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared IT substrate (A.10 conventions): SINGLETON containers pinned to the production compose
 * tags — TimescaleDB 2.17.2-pg17 with the REAL {@code deploy/flyway} lineages applied (admin
 * first, then marketdata — exactly what flyway-init does) and Redis 7.4 — reused across every IT
 * class in the module so the suite cost stays sane. Subclasses declare their own
 * {@code @SpringBootTest}; the datasource targets the {@code marketdata} schema like production.
 */
public abstract class MarketDataIntegrationTestBase {

  @ServiceConnection
  static final PostgreSQLContainer<?> TIMESCALE =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:2.17.2-pg17")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("artha")
          .withUsername("artha")
          .withPassword("it-only");

  @ServiceConnection
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  static {
    TIMESCALE.start();
    REDIS.start();
    migrate();
  }

  /** Runs the real repo lineages: admin (roles/schemas/grants) then marketdata. */
  private static void migrate() {
    Path flywayRoot = locateFlywayRoot();
    for (String lineage : new String[] {"admin", "marketdata"}) {
      Flyway.configure()
          .dataSource(TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword())
          .schemas(lineage)
          .locations("filesystem:" + flywayRoot.resolve(lineage))
          .load()
          .migrate();
    }
  }

  private static Path locateFlywayRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("deploy").resolve("flyway");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("deploy/flyway not found above " + Path.of("").toAbsolutePath());
  }

  @DynamicPropertySource
  static void marketdataSchema(DynamicPropertyRegistry registry) {
    // production parity: the service lives inside the marketdata schema
    registry.add(
        "spring.datasource.url",
        () -> {
          String url = TIMESCALE.getJdbcUrl();
          return url + (url.contains("?") ? "&" : "?") + "currentSchema=marketdata";
        });
    // migrations are applied above via the REAL deploy/flyway lineages; Boot's
    // Flyway auto-config (woken by the test-scoped flyway-core) must stay off
    registry.add("spring.flyway.enabled", () -> "false");
  }

  /** Connection details for raw-JDBC assertions (grant tests connect as other roles). */
  protected static String jdbcUrl() {
    return TIMESCALE.getJdbcUrl();
  }

  protected static String dbUser() {
    return TIMESCALE.getUsername();
  }

  protected static String dbPassword() {
    return TIMESCALE.getPassword();
  }
}
