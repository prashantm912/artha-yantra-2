package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.marketdata.backfill.AdminQueryService.QueryResult;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * SEC-02 (2026-07-18 comprehensive audit): the B5 read-only SQL console executed as the artha
 * SUPERUSER behind a VERB-only blocklist, so {@code SELECT pg_read_file('/run/secrets/...')} — which
 * the blocklist lets through — read container secrets. The fix runs the console's SQL on a dedicated
 * least-privilege {@code ay_console} login (NOSUPERUSER, SELECT-only on marketdata; admin lineage
 * V002) via its own datasource.
 *
 * <p>Two levels of proof:
 *
 * <ul>
 *   <li><b>service-level</b> — {@link AdminQueryService#run} now fails {@code pg_read_file} with
 *       <i>permission denied</i>; as the superuser it would have SUCCEEDED, so this pins that the
 *       service really executes on the least-privilege pool, and still runs a legitimate SELECT.
 *   <li><b>role-level</b> — {@code SET ROLE ay_console} then asserting the role itself cannot read
 *       server files, cannot INSERT, cannot run DDL, but CAN SELECT (defense-in-depth beyond the
 *       parser, mirroring the ay_backtest grant tests).
 * </ul>
 *
 * <p>Shared singleton DB, NO per-method cleanup — the DDL probe uses a unique table name.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class ConsoleRoleLeastPrivilegeIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private AdminQueryService adminQueryService;

  @Test
  void consoleServiceCannotReadServerFilesWithPgReadFile() {
    // pg_read_file is NOT a blocked verb — it sails past the parser and reaches Postgres, where the
    // least-privilege role refuses it. As the artha superuser this call would have returned the file.
    assertThatThrownBy(() -> adminQueryService.run("SELECT pg_read_file('/etc/hostname')", 10))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("permission denied");
  }

  @Test
  void consoleServiceStillRunsALegitimateSelect() {
    QueryResult result = adminQueryService.run("SELECT count(*) AS c FROM candles", 10);
    assertThat(result.columns()).containsExactly("c");
    assertThat(result.rowCount()).isEqualTo(1);
  }

  @Test
  void consoleRoleCannotReadFilesWriteOrRunDdlButCanSelect() throws Exception {
    String probeTable = "marketdata.sec02_probe_" + System.nanoTime();
    try (Connection connection = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE ay_console");

      // (a) cannot read server files
      assertThatThrownBy(() -> statement.executeQuery("SELECT pg_read_file('/etc/hostname')"))
          .hasMessageContaining("permission denied");

      // (b) cannot write …
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO marketdata.candles SELECT * FROM marketdata.candles WHERE false"))
          .hasMessageContaining("permission denied");

      // … and cannot run DDL
      assertThatThrownBy(() -> statement.execute("CREATE TABLE " + probeTable + " (i int)"))
          .hasMessageContaining("permission denied");

      // (c) but CAN run the legitimate SELECT the console serves
      try (var rs = statement.executeQuery("SELECT count(*) FROM marketdata.candles")) {
        assertThat(rs.next()).isTrue();
      }
    }
  }
}
