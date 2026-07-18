package in.arthayantra.marketdata.backfill;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.marketdata.config.ConsoleDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only ad-hoc SQL for the B5 Query/Scan console — lets the operator inspect the backfilled
 * candle/contract store (and value-verify a premium series) without shelling into the DB.
 *
 * <p><b>Defense in depth</b> (this is the one operator surface that runs arbitrary SQL):
 *
 * <ol>
 *   <li><b>Allowlist parse</b> — the statement MUST begin with {@code SELECT} or {@code WITH}, be a
 *       SINGLE statement (no embedded {@code ;}), and contain no data-modifying / DDL / session
 *       keyword as a whole word.
 *   <li><b>READ ONLY transaction</b> — the query runs inside {@code SET TRANSACTION READ ONLY}, so the
 *       Postgres engine itself rejects any write even if a clever statement slips the parser (e.g. a
 *       data-modifying CTE).
 *   <li><b>statement_timeout</b> + a hard <b>row cap</b> — a runaway scan can't hang the pool or OOM.
 *   <li><b>search_path = marketdata</b> — unqualified names resolve to the marketdata schema.
 *   <li><b>least-privilege login (SEC-02)</b> — the query runs on the {@link ConsoleDataSource} pool,
 *       authenticated as the {@code ay_console} role (NOSUPERUSER, SELECT-only on marketdata), NOT the
 *       artha superuser. So a statement the verb blocklist misses ({@code SELECT pg_read_file(...)}) is
 *       refused by Postgres itself — it can no longer read container secrets.
 * </ol>
 *
 * The connection comes from the dedicated least-privilege console pool; the READ ONLY transaction is
 * still the authoritative write guard, and the least-privilege role is the authoritative secret-read
 * guard. The whole surface sits behind the gateway auth filter on a loopback-only deploy.
 */
@Service
public class AdminQueryService {

  /** Hard cap for the interactive results table; export uses {@link #EXPORT_MAX_ROWS}. */
  static final int MAX_ROWS = 1_000;

  static final int EXPORT_MAX_ROWS = 50_000;
  private static final int STATEMENT_TIMEOUT_MS = 15_000;
  private static final int MAX_SQL_LEN = 20_000;

  /**
   * Whole-word tokens that must never appear — write/DDL/session/transaction verbs, plus the advisory
   * lock functions (SEC-02 fix round): a pure SELECT may call {@code pg_advisory_lock}, and SESSION
   * advisory locks survive the rollback AND the pooled connection's reuse — a generate_series
   * aggregate acquires thousands while returning one row (shared lock-memory exhaustion). No console
   * use is legitimate, so every session/xact/try/unlock variant is blocked; {@link
   * #releaseAdvisoryLocks} is the engine-level backstop for anything a future variant slips past.
   */
  private static final Set<String> BLOCKED =
      Set.of(
          "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "GRANT", "REVOKE",
          "COPY", "CALL", "DO", "MERGE", "VACUUM", "REINDEX", "REFRESH", "COMMENT", "LOCK", "SET",
          "RESET", "BEGIN", "COMMIT", "ROLLBACK", "ANALYZE", "CLUSTER", "PREPARE", "EXECUTE",
          "DEALLOCATE", "NOTIFY", "LISTEN", "DISCARD", "PG_ADVISORY_LOCK", "PG_ADVISORY_LOCK_SHARED",
          "PG_ADVISORY_XACT_LOCK", "PG_ADVISORY_XACT_LOCK_SHARED", "PG_TRY_ADVISORY_LOCK",
          "PG_TRY_ADVISORY_LOCK_SHARED", "PG_TRY_ADVISORY_XACT_LOCK",
          "PG_TRY_ADVISORY_XACT_LOCK_SHARED", "PG_ADVISORY_UNLOCK", "PG_ADVISORY_UNLOCK_SHARED",
          "PG_ADVISORY_UNLOCK_ALL");

  private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** A query result: column names + stringified cells (predictable rendering for the SQL console). */
  public record QueryResult(
      List<String> columns, List<List<String>> rows, int rowCount, boolean truncated) {}

  /** Least-privilege console pool (ay_console) — NEVER the artha superuser datasource (SEC-02). */
  private final JdbcTemplate consoleJdbc;

  private final AdminAuditLedger audit;

  public AdminQueryService(ConsoleDataSource console, AdminAuditLedger audit) {
    this.consoleJdbc = console.jdbcTemplate();
    this.audit = audit;
  }

  /** Runs a validated read-only SELECT, returning up to {@code rowLimit} rows (capped at {@value #MAX_ROWS}). */
  public QueryResult run(String sql, Integer rowLimit) {
    String cleaned = validate(sql);
    int limit = rowLimit == null ? MAX_ROWS : Math.min(Math.max(rowLimit, 1), MAX_ROWS);
    return audited(AdminAuditLedger.ACTION_QUERY, cleaned, limit);
  }

  /** Runs a validated read-only SELECT for export, returning up to {@value #EXPORT_MAX_ROWS} rows. */
  public QueryResult runForExport(String sql) {
    String cleaned = validate(sql);
    return audited(AdminAuditLedger.ACTION_QUERY_EXPORT, cleaned, EXPORT_MAX_ROWS);
  }

  /**
   * Runs {@code cleaned} up to {@code limit} rows and writes an append-only {@code admin_audit} row
   * (audit V14 — the console left zero trace before this): the SQL + its hash, the row count, the
   * truncation flag, and the wall time. A runtime failure records an error row then RETHROWS (the
   * caller's 400 is preserved); the audit write itself is fail-soft inside the ledger.
   */
  private QueryResult audited(String action, String cleaned, int limit) {
    long start = System.nanoTime();
    try {
      QueryResult result = execute(cleaned, limit);
      long durationMs = (System.nanoTime() - start) / 1_000_000L;
      audit.recordQuery(action, cleaned, limit, result.rowCount(), result.truncated(), durationMs, null);
      return result;
    } catch (CannotGetJdbcConnectionException e) {
      // FAIL-CLOSED (SEC-02): the console's ONLY datasource is the least-privilege pool. If it can't
      // connect/authenticate, the query does not run and we NEVER retry on the artha superuser.
      long durationMs = (System.nanoTime() - start) / 1_000_000L;
      audit.recordQuery(action, cleaned, limit, 0, false, durationMs, e.getMessage());
      throw new ApiException(
          500,
          ErrorCodes.INTERNAL_ERROR,
          "read-only console datasource unavailable — the query was not run (fail-closed)");
    } catch (RuntimeException e) {
      long durationMs = (System.nanoTime() - start) / 1_000_000L;
      audit.recordQuery(action, cleaned, limit, 0, false, durationMs, e.getMessage());
      throw e;
    }
  }

  private QueryResult execute(String cleaned, int limit) {
    return consoleJdbc.execute(
        (ConnectionCallback<QueryResult>)
            con -> {
              boolean prevAuto = con.getAutoCommit();
              con.setAutoCommit(false);
              try (Statement st = con.createStatement()) {
                st.execute("SET TRANSACTION READ ONLY");
                st.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS);
                st.execute("SET LOCAL search_path = marketdata, pg_catalog");
                st.setMaxRows(limit + 1);
                try (ResultSet rs = st.executeQuery(cleaned)) {
                  return read(rs, limit);
                }
              } catch (SQLException e) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "query failed: " + e.getMessage());
              } finally {
                safeRollback(con);
                con.setAutoCommit(prevAuto);
                releaseAdvisoryLocks(con);
              }
            });
  }

  /**
   * Belt-and-suspenders for the advisory-lock blocklist (SEC-02 fix round): SESSION advisory locks
   * survive rollback and ride the pooled connection back into reuse, so before the connection returns
   * to the pool every one it might hold is released. Best-effort — the blocklist is the primary guard,
   * and a cleanup hiccup must not turn a successful query into an error. Runs after autocommit is
   * restored so the call is a standalone statement, not a dangling transaction.
   */
  private static void releaseAdvisoryLocks(Connection con) {
    try (Statement st = con.createStatement()) {
      st.execute("SELECT pg_advisory_unlock_all()");
    } catch (SQLException ignored) {
      // best-effort backstop; the validator blocklist is the primary guard
    }
  }

  private static QueryResult read(ResultSet rs, int limit) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int cols = md.getColumnCount();
    List<String> columns = new ArrayList<>(cols);
    for (int i = 1; i <= cols; i++) {
      columns.add(md.getColumnLabel(i));
    }
    List<List<String>> rows = new ArrayList<>();
    boolean truncated = false;
    while (rs.next()) {
      if (rows.size() >= limit) {
        truncated = true;
        break;
      }
      List<String> row = new ArrayList<>(cols);
      for (int i = 1; i <= cols; i++) {
        Object v = rs.getObject(i);
        row.add(v == null ? null : v.toString());
      }
      rows.add(row);
    }
    return new QueryResult(columns, rows, rows.size(), truncated);
  }

  /** Validates + normalizes; throws 400 on anything not a single read-only SELECT/WITH. */
  static String validate(String sql) {
    if (sql == null || sql.isBlank()) {
      throw bad("SQL is empty");
    }
    String s = sql.strip();
    if (s.length() > MAX_SQL_LEN) {
      throw bad("query too long");
    }
    // Strip a single trailing semicolon, then reject any remaining statement separator.
    if (s.endsWith(";")) {
      s = s.substring(0, s.length() - 1).strip();
    }
    if (s.contains(";")) {
      throw bad("only a single statement is allowed");
    }
    String head = firstWord(s);
    if (!"SELECT".equals(head) && !"WITH".equals(head)) {
      throw bad("only SELECT / WITH (read-only) queries are allowed");
    }
    var m = WORD.matcher(s);
    while (m.find()) {
      if (BLOCKED.contains(m.group().toUpperCase(java.util.Locale.ROOT))) {
        throw bad("disallowed keyword: " + m.group());
      }
    }
    return s;
  }

  private static String firstWord(String s) {
    var m = WORD.matcher(s);
    return m.find() ? m.group().toUpperCase(java.util.Locale.ROOT) : "";
  }

  private static void safeRollback(Connection con) {
    try {
      con.rollback();
    } catch (SQLException ignored) {
      // best-effort; the connection is returned to the pool either way
    }
  }

  private static ApiException bad(String message) {
    return new ApiException(400, ErrorCodes.VALIDATION_FAILED, message);
  }
}
