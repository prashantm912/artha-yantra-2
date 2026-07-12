package in.arthayantra.strategysignal.signals;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code flag_snapshots} (V040) — the append-only P1-7 ledger of the behaviour-affecting
 * flag regime that governed a paper order / swing batch run. Writer only ever INSERTs.
 */
@Repository
public class FlagSnapshotRepository {

  private final JdbcTemplate jdbc;

  /** Wires the strategy datasource. */
  public FlagSnapshotRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Appends one flag-snapshot row (flags is a JSON object string; flagsHash its sha-256). */
  public void insert(
      String contextKind, String contextRef, String book, String flagsJson, String flagsHash) {
    jdbc.update(
        "INSERT INTO flag_snapshots (context_kind, context_ref, book, flags, flags_hash)"
            + " VALUES (?, ?, ?, ?::jsonb, ?)",
        contextKind, contextRef, book, flagsJson, flagsHash);
  }

  /** The snapshot rows for a context (newest first) — the read behind forensic stratification / tests. */
  public List<Map<String, Object>> query(String contextKind, String contextRef) {
    return jdbc.queryForList(
        "SELECT context_kind, context_ref, book, flags, flags_hash, captured_at FROM flag_snapshots"
            + " WHERE context_kind = ? AND context_ref = ? ORDER BY id DESC",
        contextKind, contextRef);
  }
}
