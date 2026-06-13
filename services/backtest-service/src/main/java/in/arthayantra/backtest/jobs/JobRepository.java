package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The authoritative {@code jobs} table (ADR D12 — Postgres is truth, Redis Streams are transport).
 * All claim/transition methods are idempotent against the row: a worker claims with a CONDITIONAL
 * {@code UPDATE … WHERE status='queued'}, so losing the race affects zero rows and the duplicate is
 * dropped. Connects as {@code artha}; the {@code backtest} schema is on the connection search path.
 */
@Repository
public class JobRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC + Jackson. */
  public JobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  private Job mapRow(ResultSet rs, int rowNum) throws SQLException {
    String requestJson = rs.getString("request");
    JsonNode request;
    try {
      request = objectMapper.readTree(requestJson);
    } catch (JsonProcessingException e) {
      throw new SQLException("corrupt request JSONB on job " + rs.getString("id"), e);
    }
    return new Job(
        UUID.fromString(rs.getString("id")),
        JobKind.valueOf(rs.getString("kind")),
        rs.getString("parent_job_id") == null
            ? null
            : UUID.fromString(rs.getString("parent_job_id")),
        JobStatus.fromDb(rs.getString("status")),
        rs.getInt("progress"),
        rs.getString("strategy_version_id") == null
            ? null
            : UUID.fromString(rs.getString("strategy_version_id")),
        request,
        rs.getString("error"),
        rs.getString("worker_id"),
        rs.getString("correlation_id"),
        offset(rs.getTimestamp("created_at")),
        offset(rs.getTimestamp("started_at")),
        offset(rs.getTimestamp("finished_at")));
  }

  private static OffsetDateTime offset(Timestamp ts) {
    return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
  }

  /** Inserts a {@code queued} row and returns the materialized job (with generated id + ts). */
  public Job insertQueued(
      JobKind kind,
      UUID parentJobId,
      UUID strategyVersionId,
      JsonNode request,
      String correlationId) {
    String requestJson;
    try {
      requestJson = objectMapper.writeValueAsString(request);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("job request not serializable", e);
    }
    return jdbc.queryForObject(
        """
        INSERT INTO jobs (kind, parent_job_id, strategy_version_id, request, correlation_id)
        VALUES (?, ?, ?, ?::jsonb, ?)
        RETURNING *
        """,
        this::mapRow,
        kind.name(),
        parentJobId,
        strategyVersionId,
        requestJson,
        correlationId);
  }

  /** Conditional claim: {@code running} only if still {@code queued}. Returns true on win. */
  public boolean claim(UUID id, String workerId) {
    int rows =
        jdbc.update(
            "UPDATE jobs SET status='running', worker_id=?, started_at=now() "
                + "WHERE id=? AND status='queued'",
            workerId,
            id);
    return rows == 1;
  }

  /** Clamps to [0,100] and writes progress; no-op once terminal. */
  public void updateProgress(UUID id, int progress) {
    int clamped = Math.max(0, Math.min(100, progress));
    jdbc.update(
        "UPDATE jobs SET progress=? WHERE id=? AND status='running'", (short) clamped, id);
  }

  /** Terminal transition to completed (progress forced to 100). */
  public void markCompleted(UUID id) {
    jdbc.update(
        "UPDATE jobs SET status='completed', progress=100, finished_at=now() "
            + "WHERE id=? AND status='running'",
        id);
  }

  /** Terminal transition to failed with the error message. */
  public void markFailed(UUID id, String error) {
    jdbc.update(
        "UPDATE jobs SET status='failed', error=?, finished_at=now() WHERE id=? AND status='running'",
        error,
        id);
  }

  /** Terminal transition to cancelled (from running, observed at a checkpoint). */
  public void markCancelled(UUID id) {
    jdbc.update(
        "UPDATE jobs SET status='cancelled', finished_at=now() "
            + "WHERE id=? AND status IN ('running','queued')",
        id);
  }

  /** Cancels a still-queued job before any worker claims it. Returns true if it was queued. */
  public boolean cancelIfQueued(UUID id) {
    return jdbc.update(
            "UPDATE jobs SET status='cancelled', finished_at=now() WHERE id=? AND status='queued'",
            id)
        == 1;
  }

  /**
   * Re-queues every {@code running} row (D12 crash recovery). Single-instance assumption: on
   * restart, all {@code running} rows are orphaned by the previous incarnation. Returns the count.
   */
  public int requeueStaleRunning() {
    return jdbc.update(
        "UPDATE jobs SET status='queued', worker_id=NULL, started_at=NULL WHERE status='running'");
  }

  /** Ids of all queued jobs — re-dispatched on startup so every queued job has a stream entry. */
  public List<UUID> findQueuedIds() {
    return jdbc.query(
        "SELECT id FROM jobs WHERE status='queued' ORDER BY created_at",
        (rs, n) -> UUID.fromString(rs.getString("id")));
  }

  /** Single job by id. */
  public Optional<Job> find(UUID id) {
    return jdbc.query("SELECT * FROM jobs WHERE id=?", this::mapRow, id).stream().findFirst();
  }

  /** Paged listing, optionally filtered by status and by {@code request->>'strategyId'}. */
  public List<Job> list(JobStatus status, String strategyId, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT * FROM jobs WHERE 1=1");
    java.util.List<Object> args = new java.util.ArrayList<>();
    if (status != null) {
      sql.append(" AND status=?");
      args.add(status.db());
    }
    if (strategyId != null && !strategyId.isBlank()) {
      sql.append(" AND request->>'strategyId'=?");
      args.add(strategyId);
    }
    sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapRow, args.toArray());
  }

  /**
   * Prunes terminal-state {@code jobs} rows older than {@code days} (plan §6.5 stale-job hygiene).
   * Phase 30 adds the {@code NOT EXISTS backtest_runs} guard so the research record is never pruned.
   */
  public int pruneStaleTerminal(int days) {
    return jdbc.update(
        "DELETE FROM jobs WHERE status IN ('completed','failed','cancelled') "
            + "AND finished_at < now() - make_interval(days => ?)",
        days);
  }

  /** Live-status counts for the {@code jobs:summary} rollup. */
  public long countByStatus(JobStatus status) {
    Long c =
        jdbc.queryForObject(
            "SELECT count(*) FROM jobs WHERE status=?", Long.class, status.db());
    return c == null ? 0L : c;
  }
}
