package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writer for the {@code strategy.paper_admin_audit} trail (app-platform audit 2026-07-10 §7.1/§7.2.5) —
 * the append-only record of the two DESTRUCTIVE paper-book admin actions that left ZERO trace before
 * this: a book RESET (wipes positions + orders) and a CAPITAL_CHANGE (edits a book's starting capital).
 *
 * <p>Two invariants (mirroring the A4 ingest-run ledger):
 *
 * <ol>
 *   <li><b>Append-only.</b> Only INSERT — there is no UPDATE/DELETE method here.
 *   <li><b>Fail-soft.</b> Every write is wrapped: an audit-DB hiccup must never break the action it
 *       audits. A failed insert is logged and dropped (the action still ran, just unaudited).
 * </ol>
 */
@Repository
public class PaperAdminAuditLedger {

  /** The audited actions. Constants keep call sites typo-proof + discoverable. */
  public static final String ACTION_RESET = "RESET";
  public static final String ACTION_CAPITAL_CHANGE = "CAPITAL_CHANGE";

  private static final Logger log = LoggerFactory.getLogger(PaperAdminAuditLedger.class);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public PaperAdminAuditLedger(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** Record a book wipe ({@code book} null = all books) with the deleted position/order counts. */
  public void recordReset(String book, int positionsDeleted, int ordersDeleted) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("positionsDeleted", positionsDeleted);
    detail.put("ordersDeleted", ordersDeleted);
    record(ACTION_RESET, book, detail);
  }

  /** Record an owner edit of a book's starting capital (money as string — the money-as-string convention). */
  public void recordCapitalChange(String book, BigDecimal previous, BigDecimal updated) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("previous", previous == null ? null : previous.toPlainString());
    detail.put("new", updated == null ? null : updated.toPlainString());
    record(ACTION_CAPITAL_CHANGE, book, detail);
  }

  private void record(String action, String book, Map<String, Object> detail) {
    try {
      jdbc.update(
          "INSERT INTO paper_admin_audit (action, book, detail) VALUES (?, ?, ?::jsonb)",
          action,
          book,
          json(detail));
    } catch (RuntimeException e) {
      log.warn(
          "paper_admin_audit {} write failed — action unaudited (non-fatal): {}", action, e.getMessage());
    }
  }

  private String json(Map<String, Object> detail) {
    try {
      return mapper.writeValueAsString(detail);
    } catch (JsonProcessingException e) {
      return "{}"; // never break the audit over a serialization edge; the row still lands
    }
  }
}
