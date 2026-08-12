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
 * tags — TimescaleDB 2.18.2-pg17 with the REAL {@code deploy/flyway} lineages applied (admin
 * first, then marketdata — exactly what flyway-init does) and Redis 7.4 — reused across every IT
 * class in the module so the suite cost stays sane. Subclasses declare their own
 * {@code @SpringBootTest}; the datasource targets the {@code marketdata} schema like production.
 */
public abstract class MarketDataIntegrationTestBase {

  // NOTE: no @ServiceConnection here — its ConnectionDetails would override the
  // currentSchema=marketdata URL the service needs; explicit properties below.
  static final PostgreSQLContainer<?> TIMESCALE =
      new PostgreSQLContainer<>(
              DockerImageName.parse("timescale/timescaledb:2.18.2-pg17")
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
    setConsoleRolePassword();
  }

  /**
   * SEC-02: admin lineage V002 creates {@code ay_console} with PASSWORD NULL; the compose
   * {@code console-role-init} one-shot sets its password in the live/mock stack. Tests have no
   * one-shot, so set the documented mock/CI default here — matching the ConsoleDataSource default so
   * the console datasource (and the least-privilege ITs) can authenticate as ay_console.
   */
  private static void setConsoleRolePassword() {
    try (var c =
            java.sql.DriverManager.getConnection(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        var st = c.createStatement()) {
      st.execute("ALTER ROLE ay_console LOGIN PASSWORD 'console-readonly-local'");
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("failed setting ay_console test password", e);
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
    registry.add("spring.datasource.username", TIMESCALE::getUsername);
    registry.add("spring.datasource.password", TIMESCALE::getPassword);
    // migrations are applied above via the REAL deploy/flyway lineages; Boot's
    // Flyway auto-config (woken by the test-scoped flyway-core) must stay off
    registry.add("spring.flyway.enabled", () -> "false");
    // ⚠️ Bhavcopy startup catch-up is OFF for EVERY context that shares this substrate
    // (task_06ad72b6, 2026-07-31). The first fix for the catch-up deadlock scoped the property to
    // "tests that manipulate bhavcopy tables" — BhavcopyBackfillIntegrationTest sets it — and that
    // was the WRONG SCOPE: Spring caches contexts, every OTHER context boots with catch-up ON, and
    // runIfFree() is fire-and-forget on the service's own executor, so a recently-booted cached
    // context can still be mid-write to nse_eod_bhavcopy when ANY test's whole-table DELETE runs.
    // On the 2-core CI runner that re-deadlocked 3-of-3 surefire attempts (PR #1138's shard) with
    // the property "fix" already in place. The hazard is any-writer-vs-any-test, so the default
    // lives here, on the shared substrate all 67 IT contexts extend. ⚠️ There is NO in-hierarchy
    // opt-out (review round 1): subclass @DynamicPropertySource methods run BEFORE this one, so
    // this registration wins LAST, and @SpringBootTest(properties=...) loses to DynamicPropertySource
    // too — both fail SILENTLY toward disabled. A test that genuinely wants the catch-up/cron path
    // must NOT extend this base (own substrate), which is the right amount of friction.
    registry.add("artha.bhavcopy.startup-catchup", () -> "false");
    // ...and the SCHEDULED writer too (review round 1): the 19:30 IST eod-cron is 14:00 UTC —
    // prime CI hours — so a run crossing it re-creates the identical cached-context-writer race
    // the line above closes. Spring cron "-" = disabled.
    registry.add("artha.bhavcopy.eod-cron", () -> "-");
    // ⚠️ Same rule for the morning-canary BOOT catch-up (task_e2e01c): MorningCanaryCatchUp fires
    // on ApplicationReadyEvent and dispatches onto monitorTaskScheduler, so a CACHED context can
    // sweep ingest_runs and INSERT into canary_runs at an arbitrary later moment — inside whichever
    // unrelated test happens to be running. That is the identical cached-context-writer hazard the
    // two lines above exist for, so the default lives here too, on the substrate all IT contexts
    // extend. IngestCoverageCanaryIntegrationTest calls catchUpIfMissed() DIRECTLY on a
    // hand-constructed canary with a fixed clock — that is how the path stays covered without any
    // context firing it on its own.
    registry.add("artha.ingest-canary.startup-catchup", () -> "false");
  }

  /** Connection details for raw-JDBC assertions (grant tests connect as other roles). */
  protected static String jdbcUrl() {
    return TIMESCALE.getJdbcUrl();
  }

  /** Admin user for raw-JDBC assertions. */
  protected static String dbUser() {
    return TIMESCALE.getUsername();
  }

  /** Admin password for raw-JDBC assertions. */
  protected static String dbPassword() {
    return TIMESCALE.getPassword();
  }
}
