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
  private final EngineIdentity engineIdentity;

  /** Wires JDBC + Jackson + the engine identity stamped onto each queued row (audit P0-2 / R1). */
  public JobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper, EngineIdentity engineIdentity) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.engineIdentity = engineIdentity;
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
        offset(rs.getTimestamp("finished_at")),
        rs.getString("created_by"));
  }

  private static OffsetDateTime offset(Timestamp ts) {
    return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
  }

  /**
   * Inserts a {@code queued} row and returns the materialized job (with generated id + ts).
   * {@code createdBy} is the submitting actor (audit T3 / EVO §13 row 4) — {@code 'owner'} on the
   * API path; the optimizer's own OPTIMIZATION/TRIAL rows are written by the Python writer, not here.
   */
  public Job insertQueued(
      JobKind kind,
      UUID parentJobId,
      UUID strategyVersionId,
      JsonNode request,
      String correlationId,
      String createdBy) {
    String requestJson;
    try {
      requestJson = objectMapper.writeValueAsString(request);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("job request not serializable", e);
    }
    return jdbc.queryForObject(
        """
        INSERT INTO jobs (kind, parent_job_id, strategy_version_id, request, correlation_id,
                          engine_sha, engine_image, created_by)
        VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        RETURNING *
        """,
        this::mapRow,
        kind.name(),
        parentJobId,
        strategyVersionId,
        requestJson,
        correlationId,
        engineIdentity.sha(),
        engineIdentity.image(),
        createdBy);
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
   * Re-queues this service's orphaned {@code running} rows (D12 crash recovery). Single-instance
   * assumption for BACKTEST/TRIAL only — OPTIMIZATION rows are owned by the optimizer's sweep
   * thread (a separate container a backtest restart does NOT orphan): without the kind filter a
   * restart hijacked a live sweep row, replayed it as a plain backtest and marked the sweep
   * completed under the optimizer (audit P1-10). Returns the count.
   */
  public int requeueStaleRunning() {
    return jdbc.update(
        "UPDATE jobs SET status='queued', worker_id=NULL, started_at=NULL"
            + " WHERE status='running' AND kind IN ('BACKTEST','TRIAL')");
  }

  /** Ids of queued BACKTEST/TRIAL jobs — re-dispatched on startup (never OPTIMIZATION rows). */
  public List<UUID> findQueuedIds() {
    return jdbc.query(
        "SELECT id FROM jobs WHERE status='queued' AND kind IN ('BACKTEST','TRIAL')"
            + " ORDER BY created_at",
        (rs, n) -> UUID.fromString(rs.getString("id")));
  }

  /** Single job by id. */
  public Optional<Job> find(UUID id) {
    return jdbc.query("SELECT * FROM jobs WHERE id=?", this::mapRow, id).stream().findFirst();
  }

  /**
   * Whitelist of FE column-id → SQL sort expression (server-side sort so the header sort spans EVERY
   * page, not just the loaded one). The completed-run return is sorted via a deduped LEFT JOIN on
   * {@code backtest_runs} (one row/job by MAX so the join can't inflate the page). Any unlisted key
   * falls back to {@code created_at}.
   */
  private static final java.util.Map<String, String> SORT_COLUMNS =
      java.util.Map.of(
          "createdAt", "jobs.created_at",
          "status", "jobs.status",
          "kind", "jobs.kind",
          "progress", "jobs.progress",
          "startedAt", "jobs.started_at",
          "finishedAt", "jobs.finished_at",
          "totalReturn", "r.total_return",
          "strategyId", "jobs.request->>'strategyId'",
          "id", "jobs.id");

  /**
   * Paged listing, optionally filtered by status and {@code request->>'strategyId'} and ordered by a
   * whitelisted {@code sortBy}/{@code sortDir} (default {@code created_at DESC}). The
   * {@code backtest_runs} join is for the return-sort only; the row mapping stays {@code jobs.*}.
   */
  public List<Job> list(
      JobStatus status,
      String strategyId,
      String strategyIds,
      String currentVersions,
      int limit,
      int offset,
      String sortBy,
      String sortDir) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT jobs.* FROM jobs "
                + "LEFT JOIN (SELECT job_id, MAX(total_return) AS total_return FROM backtest_runs "
                + "GROUP BY job_id) r ON r.job_id = jobs.id WHERE 1=1");
    java.util.List<Object> args = new java.util.ArrayList<>();
    if (status != null) {
      sql.append(" AND jobs.status=?");
      args.add(status.db());
    }
    if (strategyId != null && !strategyId.isBlank()) {
      sql.append(" AND jobs.request->>'strategyId'=?");
      args.add(strategyId);
    }
    // CSV of strategy UUIDs (the Jobs page's tag filter resolves selected tags → matching strategies).
    // = ANY(string_to_array(...)) keeps the bind a single CSV param (no dynamic placeholder count).
    if (strategyIds != null && !strategyIds.isBlank()) {
      sql.append(" AND jobs.request->>'strategyId' = ANY(string_to_array(?, ','))");
      args.add(strategyIds);
    }
    // "Latest version only": CSV of "strategyId:version" pairs (each strategy's CURRENT version).
    // Matched against the request JSONB, NOT the strategy_version_id column — TRIAL + OPTIMIZATION
    // rows leave that column NULL (only BACKTEST submits set it), so a column match dropped every
    // trial. The (strategyId, version) pair is unique per the registry and is exactly what the row's
    // is-latest badge compares, so filter and badge agree. '__none__' matches no pair → empty page.
    if (currentVersions != null && !currentVersions.isBlank()) {
      sql.append(
          " AND (jobs.request->>'strategyId' || ':' || (jobs.request->>'strategyVersion'))"
              + " = ANY(string_to_array(?, ','))");
      args.add(currentVersions);
    }
    // NB: Map.of#getOrDefault throws on a null key — guard before the lookup (a no-sortBy request,
    // e.g. a tag-only filter call, otherwise NPEs).
    String col = sortBy == null ? "jobs.created_at" : SORT_COLUMNS.getOrDefault(sortBy, "jobs.created_at");
    String dir = "asc".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
    sql.append(" ORDER BY ")
        .append(col)
        .append(' ')
        .append(dir)
        .append(" NULLS LAST, jobs.created_at DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), this::mapRow, args.toArray());
  }

  /**
   * Prunes terminal-state {@code jobs} rows older than {@code days} (plan §6.5 stale-job hygiene).
   * NOT-EXISTS guards keep the research record (runs / trial ledger / parent sweeps) unprunable —
   * the javadoc long claimed the runs guard but the SQL lacked it, so the first run-bearing job to
   * age past the window made the whole monthly DELETE abort on its FK, forever (audit P1-10).
   */
  public int pruneStaleTerminal(int days) {
    return jdbc.update(
        "DELETE FROM jobs WHERE status IN ('completed','failed','cancelled') "
            + "AND finished_at < now() - make_interval(days => ?) "
            + "AND NOT EXISTS (SELECT 1 FROM backtest_runs r WHERE r.job_id = jobs.id) "
            + "AND NOT EXISTS (SELECT 1 FROM optimization_trials t WHERE t.sweep_job_id = jobs.id) "
            + "AND NOT EXISTS (SELECT 1 FROM jobs c WHERE c.parent_job_id = jobs.id)",
        days);
  }

  /** Live-status counts for the {@code jobs:summary} rollup. */
  public long countByStatus(JobStatus status) {
    Long c =
        jdbc.queryForObject(
            "SELECT count(*) FROM jobs WHERE status=?", Long.class, status.db());
    return c == null ? 0L : c;
  }

  /**
   * One prior lineage job's tested window, for the S1C stress guard (§D.6): the job id plus its raw
   * {@code request->>'from'} / {@code request->>'to'} strings (a plain date or a full offset
   * date-time — normalized to IST in Java by the caller, the same {@code toDateTime} convention the
   * rest of the service uses). Date ranges live in {@code jobs.request}; both BACKTEST and
   * OPTIMIZATION/TRIAL jobs leak holdout information, so kind is NOT filtered here.
   *
   * @param id the job id
   * @param fromRaw {@code request->>'from'} (may be {@code null}/blank for jobs without a window)
   * @param toRaw {@code request->>'to'}
   */
  public record LineageWindow(UUID id, String fromRaw, String toRaw) {}

  /**
   * Every PRIOR job of a strategy lineage that carries a tested {@code [from, to]} window, in
   * creation order — the overlap test is performed in Java (the {@code ?} JSONB operator collides
   * with JDBC bind placeholders, and the date strings need the service's IST normalization anyway).
   * Filtered to {@code request->>'strategyId'} = the lineage; excludes the given job id (the
   * about-to-be-submitted stress job itself) when non-null.
   */
  public List<LineageWindow> findLineageWindows(String strategyId, UUID excludeJobId) {
    String exclude = excludeJobId == null ? null : excludeJobId.toString();
    return jdbc.query(
        """
        SELECT id, request->>'from' AS from_raw, request->>'to' AS to_raw
        FROM jobs
        WHERE request->>'strategyId' = ?
          AND (?::uuid IS NULL OR id <> ?::uuid)
        ORDER BY created_at
        """,
        (rs, n) ->
            new LineageWindow(
                UUID.fromString(rs.getString("id")),
                rs.getString("from_raw"),
                rs.getString("to_raw")),
        strategyId,
        exclude,
        exclude);
  }
}
