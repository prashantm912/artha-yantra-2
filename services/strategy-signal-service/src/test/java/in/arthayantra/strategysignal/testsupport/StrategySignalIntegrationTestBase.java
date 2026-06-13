package in.arthayantra.strategysignal.testsupport;

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
 * Shared IT substrate (the Stage-B pattern): SINGLETON containers pinned to the production
 * compose tags — TimescaleDB 2.17.2-pg17 with the REAL {@code deploy/flyway} lineages applied
 * (admin first, then strategy — exactly what flyway-init does) and Redis 7.4 — reused across
 * every IT class in the module. The datasource targets the {@code strategy} schema like
 * production; the repeatable seed migration runs too, so the sample draft exists in every IT.
 */
public abstract class StrategySignalIntegrationTestBase {

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

  private static void migrate() {
    Path flywayRoot = locateFlywayRoot();
    for (String lineage : new String[] {"admin", "strategy"}) {
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
    throw new IllegalStateException(
        "deploy/flyway not found above " + Path.of("").toAbsolutePath());
  }

  @DynamicPropertySource
  static void strategySchema(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> {
          String url = TIMESCALE.getJdbcUrl();
          return url + (url.contains("?") ? "&" : "?") + "currentSchema=strategy";
        });
    registry.add("spring.datasource.username", TIMESCALE::getUsername);
    registry.add("spring.datasource.password", TIMESCALE::getPassword);
    registry.add("spring.flyway.enabled", () -> "false");
  }

  /** Raw-JDBC connection details (grant tests SET ROLE off this). */
  protected static String jdbcUrl() {
    return TIMESCALE.getJdbcUrl();
  }

  /** Admin user. */
  protected static String dbUser() {
    return TIMESCALE.getUsername();
  }

  /** Admin password. */
  protected static String dbPassword() {
    return TIMESCALE.getPassword();
  }
}
