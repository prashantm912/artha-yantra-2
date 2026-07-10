package in.arthayantra.marketdata.backfill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writer for the {@code marketdata.admin_audit} trail (app-platform audit 2026-07-10 §8 V14) — the
 * append-only record of the two privileged operator surfaces that left ZERO trace before this: the B5
 * read-only SQL console and the B6 backfilled-candle export.
 *
 * <p>Two invariants (mirroring the A4 ingest-run ledger):
 *
 * <ol>
 *   <li><b>Append-only.</b> Only INSERT — there is no UPDATE/DELETE method here.
 *   <li><b>Fail-soft.</b> Every write is wrapped: an audit-DB hiccup must never break the read it
 *       audits. A failed insert is logged and dropped (the operator's work still ran, just unaudited).
 * </ol>
 *
 * V14's prescription — "sql-hash/params/rows/duration for queries; contract-set/row-counts for
 * exports" — maps to {@link #recordQuery} (console) and {@link #recordExport} (candle export).
 */
@Repository
public class AdminAuditLedger {

  /** The audited actions. Constants keep call sites typo-proof + discoverable. */
  public static final String ACTION_QUERY = "QUERY";
  public static final String ACTION_QUERY_EXPORT = "QUERY_EXPORT";
  public static final String ACTION_CANDLE_EXPORT = "CANDLE_EXPORT";
  public static final String ACTION_CANDLE_EXPORT_BULK = "CANDLE_EXPORT_BULK";

  private static final Logger log = LoggerFactory.getLogger(AdminAuditLedger.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AdminAuditLedger(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /**
   * Record a console execution (action {@code QUERY} or {@code QUERY_EXPORT}): the executed SQL + its
   * SHA-256, the effective row limit, the row count, whether the cap truncated the result, and the wall
   * time. {@code error} is null on success, the exception message when the query itself threw.
   */
  public void recordQuery(
      String action,
      String sql,
      int rowLimit,
      long rowCount,
      boolean truncated,
      long durationMs,
      String error) {
    try {
      jdbc.update(
          "INSERT INTO admin_audit"
              + " (action, sql_text, sql_hash, detail, row_count, truncated, duration_ms, error)"
              + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
          action,
          sql,
          sha256(sql),
          json(Map.of("rowLimit", rowLimit)),
          rowCount,
          truncated,
          durationMs,
          error);
    } catch (RuntimeException e) {
      log.warn("admin_audit {} write failed — action unaudited (non-fatal): {}", action, e.getMessage());
    }
  }

  /**
   * Record a candle export (action {@code CANDLE_EXPORT} or {@code CANDLE_EXPORT_BULK}): the request's
   * contract-set as {@code detail}, the exported row count, and whether the per-contract row cap
   * truncated any contract.
   */
  public void recordExport(String action, Map<String, Object> params, long rowCount, boolean truncated) {
    try {
      jdbc.update(
          "INSERT INTO admin_audit (action, detail, row_count, truncated) VALUES (?, ?::jsonb, ?, ?)",
          action,
          json(params),
          rowCount,
          truncated);
    } catch (RuntimeException e) {
      log.warn("admin_audit {} write failed — action unaudited (non-fatal): {}", action, e.getMessage());
    }
  }

  private String json(Map<String, Object> detail) {
    try {
      return mapper.writeValueAsString(detail);
    } catch (JsonProcessingException e) {
      return "{}"; // never break the audit over a serialization edge; the row still lands
    }
  }

  private static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      return null; // SHA-256 is always present on a JVM; degrade to a null hash rather than throw
    }
  }
}
