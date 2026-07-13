package in.arthayantra.backtest.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.DecisionTraceCollector.Trace;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Batch persistence and ordered reads for per-day rejected-entry diagnostics. */
@Repository
public class DecisionTraceRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC and Jackson. */
  public DecisionTraceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** Inserts a run's complete rollup in one JDBC batch. */
  public void insertAll(UUID runId, List<Trace> traces) {
    if (traces == null || traces.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        """
        INSERT INTO backtest_decision_days (
          run_id, session_date, reason, bars, max_composite, sample_bucket, sample_breakdown)
        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        traces,
        traces.size(),
        (statement, trace) -> bind(statement, runId, trace));
  }

  /** Ordered rows, empty-list for a known untraced run, or empty optional for an unknown run. */
  public Optional<List<Trace>> findByRun(UUID runId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM backtest_runs WHERE id=?)", Boolean.class, runId);
    if (!Boolean.TRUE.equals(exists)) {
      return Optional.empty();
    }
    return Optional.of(
        jdbc.query(
            """
            SELECT session_date, reason, bars, max_composite, sample_bucket, sample_breakdown
            FROM backtest_decision_days
            WHERE run_id=?
            ORDER BY session_date, reason
            """,
            (rs, row) ->
                new Trace(
                    rs.getObject("session_date", java.time.LocalDate.class),
                    rs.getString("reason"),
                    rs.getInt("bars"),
                    rs.getBigDecimal("max_composite"),
                    rs.getObject("sample_bucket", java.time.OffsetDateTime.class),
                    parse(rs.getString("sample_breakdown"))),
            runId));
  }

  private void bind(PreparedStatement statement, UUID runId, Trace trace) throws SQLException {
    statement.setObject(1, runId);
    statement.setObject(2, trace.sessionDate());
    statement.setString(3, trace.reason());
    statement.setInt(4, trace.bars());
    statement.setBigDecimal(5, trace.maxComposite());
    statement.setObject(6, trace.sampleBucket());
    statement.setString(7, json(trace.sampleBreakdown()));
  }

  private String json(JsonNode node) {
    if (node == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("decision trace JSONB not serializable", e);
    }
  }

  private JsonNode parse(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("corrupt decision trace JSONB", e);
    }
  }
}
