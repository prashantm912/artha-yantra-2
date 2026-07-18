package in.arthayantra.marketdata.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SEC-02: a SEPARATE, least-privilege datasource for the B5 read-only SQL console. The console runs
 * arbitrary operator SQL; executing it as the artha SUPERUSER let {@code SELECT
 * pg_read_file('/run/secrets/...')} read container secrets straight past the verb-only blocklist.
 * This pool authenticates as the dedicated {@code ay_console} role (NOSUPERUSER, SELECT-only on the
 * {@code marketdata} schema — admin lineage V002), so {@code pg_read_file} / writes / DDL are refused
 * by Postgres itself, not merely by the parser.
 *
 * <p><b>Deliberately NOT a {@link javax.sql.DataSource} or {@code JdbcOperations} bean.</b> Either
 * type would trip Spring Boot's {@code @ConditionalOnMissingBean} and switch OFF the service's PRIMARY
 * auto-configured datasource / JdbcTemplate — which every repository and the audit ledger depend on.
 * So the least-privilege pool lives here and is reached only through {@link #jdbcTemplate()}; the
 * primary (artha) datasource is untouched.
 *
 * <p><b>Fail-closed.</b> The pool is lazy ({@code initializationFailTimeout = -1}, {@code minimumIdle
 * = 0}) so a missing or misconfigured {@code ay_console} role can never block service boot. On the
 * first console query a bad credential surfaces as a clear error (mapped by {@code AdminQueryService})
 * and NEVER falls back to the artha datasource — this holder is the only path the console can take.
 *
 * <p>The URL is reused verbatim from {@code spring.datasource.url} (same host / database / {@code
 * currentSchema=marketdata} as the primary), only the login differs. The role's password is set by the
 * {@code console-role-init} compose one-shot from the same {@code ARTHA_CONSOLE_DB_PASSWORD}, so the
 * app and the role always agree; a single shared {@code .env} keeps mock and live consistent across a
 * profile switch (the role is cluster-global).
 */
@Component
public class ConsoleDataSource implements AutoCloseable {

  private final HikariDataSource dataSource;
  private final JdbcTemplate jdbcTemplate;

  public ConsoleDataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${artha.console.datasource.username:ay_console}") String username,
      @Value("${artha.console.datasource.password:console-readonly-local}") String password) {
    HikariConfig cfg = new HikariConfig();
    cfg.setPoolName("console-readonly");
    cfg.setJdbcUrl(url);
    cfg.setUsername(username);
    cfg.setPassword(password);
    cfg.setReadOnly(true); // engine-level backstop; the READ ONLY transaction is still the authority
    cfg.setMaximumPoolSize(3); // the console is interactive + rare; a tiny pool is plenty
    cfg.setMinimumIdle(0); // lazy — no connection opened until the first console query
    cfg.setConnectionTimeout(5_000); // fail fast + clear when the role can't connect/authenticate
    cfg.setValidationTimeout(3_000);
    cfg.setInitializationFailTimeout(-1L); // never fail service boot on a bad/absent console role
    this.dataSource = new HikariDataSource(cfg);
    this.jdbcTemplate = new JdbcTemplate(this.dataSource);
  }

  /** A {@link JdbcTemplate} bound to the least-privilege console pool (the console's only DB path). */
  public JdbcTemplate jdbcTemplate() {
    return jdbcTemplate;
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
