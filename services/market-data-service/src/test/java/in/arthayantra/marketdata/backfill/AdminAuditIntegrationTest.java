package in.arthayantra.marketdata.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Audit V14 (app-platform audit 2026-07-10 §8): the two privileged market-data admin surfaces — the B5
 * read-only SQL console (POST /api/v1/market/admin/query + /query/export) and the B6 candle export —
 * left ZERO trace before this. These ITs run against the real Timescale container with the real
 * {@code deploy/flyway} marketdata lineage (so V042 runs exactly as in prod) and pin: the {@code
 * admin_audit} table exists post-Flyway; a console execution writes a QUERY row carrying the SQL text +
 * a duration; a query that clips at the row cap records truncated=true; a CSV export writes a
 * QUERY_EXPORT row AND surfaces the truncation to the caller as an {@code X-Result-Truncated} header;
 * and the candle-export ledger call writes a CANDLE_EXPORT row with the contract-set + truncation flag.
 *
 * <p>Shared singleton DB with NO per-method cleanup — each method embeds a unique marker (UUID) in its
 * SQL/detail so its audit row is found without colliding with siblings or surefire reruns.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
@AutoConfigureMockMvc
class AdminAuditIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private AdminAuditLedger ledger;

  @Test
  void tableExistsAfterFlyway() {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'marketdata' AND table_name = 'admin_audit'",
            Integer.class);
    assertThat(n).isEqualTo(1);
  }

  @Test
  void runningAQueryWritesAQueryAuditRowWithTheSqlAndDuration() throws Exception {
    String marker = marker();
    String sql = "SELECT 1 AS n /* " + marker + " */";

    mockMvc
        .perform(
            post("/api/v1/market/admin/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("sql", sql))))
        .andExpect(status().isOk());

    Map<String, Object> row = auditRow("QUERY", marker);
    assertThat((String) row.get("sql_text")).contains(marker);
    assertThat(row.get("sql_hash")).isNotNull();
    assertThat(((Number) row.get("row_count")).longValue()).isEqualTo(1L);
    assertThat((Boolean) row.get("truncated")).isFalse();
    assertThat(row.get("duration_ms")).isNotNull(); // wall time recorded (>= 0, may be 0 for SELECT 1)
    assertThat(row.get("created_at")).isNotNull();
  }

  @Test
  void aQueryClippedByTheRowCapRecordsTruncatedTrue() throws Exception {
    String marker = marker();
    String sql = "SELECT generate_series(1, 10) AS n /* " + marker + " */";

    mockMvc
        .perform(
            post("/api/v1/market/admin/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("sql", sql, "rowLimit", 5))))
        .andExpect(status().isOk());

    Map<String, Object> row = auditRow("QUERY", marker);
    assertThat((Boolean) row.get("truncated")).isTrue();
    assertThat(((Number) row.get("row_count")).longValue()).isEqualTo(5L);
  }

  @Test
  void aSmallCsvExportWritesAnUntruncatedQueryExportRowAndHeader() throws Exception {
    String marker = marker();
    String sql = "SELECT generate_series(1, 3) AS n /* " + marker + " */";

    mockMvc
        .perform(
            post("/api/v1/market/admin/query/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("sql", sql, "format", "csv"))))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Result-Truncated", "false"))
        .andExpect(header().string("X-Result-Rows", "3"));

    Map<String, Object> row = auditRow("QUERY_EXPORT", marker);
    assertThat((Boolean) row.get("truncated")).isFalse();
    assertThat(((Number) row.get("row_count")).longValue()).isEqualTo(3L);
  }

  @Test
  void aCsvExportClippedByTheRowCapMakesTruncationExplicit() throws Exception {
    String marker = marker();
    // 50_001 rows vs the 50_000 export cap → the result is truncated; the header + audit row must say so.
    String sql = "SELECT generate_series(1, 50001) AS n /* " + marker + " */";

    mockMvc
        .perform(
            post("/api/v1/market/admin/query/export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("sql", sql, "format", "csv"))))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Result-Truncated", "true"))
        .andExpect(header().string("X-Result-Rows", "50000"));

    Map<String, Object> row = auditRow("QUERY_EXPORT", marker);
    assertThat((Boolean) row.get("truncated")).isTrue();
    assertThat(((Number) row.get("row_count")).longValue()).isEqualTo(50000L);
  }

  @Test
  void aCandleExportWritesACandleExportRowWithTheContractSetAndTruncationFlag() {
    String underlying = "NIFTY-" + UUID.randomUUID();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("underlying", underlying);
    detail.put("expiry", "2026-07-31");
    detail.put("format", "csv");

    ledger.recordExport(AdminAuditLedger.ACTION_CANDLE_EXPORT, detail, 100_000, true);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT action, row_count, truncated, detail->>'underlying' AS underlying, created_at "
                + "FROM admin_audit WHERE action = 'CANDLE_EXPORT' AND detail->>'underlying' = ?",
            underlying);
    assertThat(row.get("action")).isEqualTo("CANDLE_EXPORT");
    assertThat(((Number) row.get("row_count")).longValue()).isEqualTo(100_000L);
    assertThat((Boolean) row.get("truncated")).isTrue();
    assertThat(row.get("underlying")).isEqualTo(underlying);
    assertThat(row.get("created_at")).isNotNull();
  }

  private Map<String, Object> auditRow(String action, String marker) {
    return jdbc.queryForMap(
        "SELECT sql_text, sql_hash, row_count, truncated, duration_ms, created_at "
            + "FROM admin_audit WHERE action = ? AND sql_text LIKE ?",
        action,
        "%" + marker + "%");
  }

  private static String marker() {
    return "q" + UUID.randomUUID().toString().replace("-", "");
  }
}
