package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.common.web.error.ApiException;
import org.junit.jupiter.api.Test;

/**
 * The B5 query-console allowlist is the one operator surface running arbitrary SQL, so its validator
 * is pinned: SELECT/WITH only, single statement, no write/DDL/session keyword. The READ ONLY
 * transaction is the engine-level backstop, but this parse is the first gate.
 */
class AdminQueryServiceTest {

  @Test
  void acceptsReadOnlySelectAndWith() {
    assertThat(AdminQueryService.validate("SELECT 1")).isEqualTo("SELECT 1");
    assertThat(AdminQueryService.validate("select * from candles where exchange = 'NFO'"))
        .startsWith("select * from candles");
    assertThat(AdminQueryService.validate("WITH x AS (SELECT 1 AS n) SELECT n FROM x"))
        .startsWith("WITH x");
    // a single trailing semicolon is tolerated (stripped)
    assertThat(AdminQueryService.validate("SELECT 1;")).isEqualTo("SELECT 1");
    // OFFSET contains the substring 'SET' but is a distinct word → allowed
    assertThat(AdminQueryService.validate("SELECT close FROM candles OFFSET 5"))
        .contains("OFFSET");
  }

  @Test
  void rejectsWritesDdlAndSessionVerbs() {
    for (String sql :
        new String[] {
          "INSERT INTO candles VALUES (1)",
          "UPDATE candles SET close = 0",
          "DELETE FROM candles",
          "DROP TABLE candles",
          "ALTER TABLE candles ADD COLUMN x int",
          "TRUNCATE candles",
          "GRANT ALL ON candles TO public",
          "SET ROLE postgres",
          "RESET ROLE",
          "VACUUM candles",
          "REFRESH MATERIALIZED VIEW candles_1d",
          // SEC-02 fix round: session advisory locks survive rollback + pooled reuse
          "SELECT pg_advisory_lock(42)",
          "SELECT PG_ADVISORY_LOCK(42)",
          "SELECT pg_try_advisory_lock_shared(1, 2) FROM generate_series(1, 100000)",
          "SELECT pg_advisory_xact_lock(7)",
          "SELECT pg_advisory_unlock_all()"
        }) {
      assertThatThrownBy(() -> AdminQueryService.validate(sql))
          .as("must reject: %s", sql)
          .isInstanceOf(ApiException.class);
    }
  }

  @Test
  void rejectsMultiStatementAndDataModifyingCte() {
    assertThatThrownBy(() -> AdminQueryService.validate("SELECT 1; DROP TABLE candles"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                AdminQueryService.validate(
                    "WITH d AS (DELETE FROM candles RETURNING *) SELECT * FROM d"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void rejectsEmptyAndNonSelectHead() {
    assertThatThrownBy(() -> AdminQueryService.validate("")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> AdminQueryService.validate("   ")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> AdminQueryService.validate("EXPLAIN SELECT 1"))
        .isInstanceOf(ApiException.class);
  }
}
