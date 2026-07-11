package in.arthayantra.backtest.replay.counterfactual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.replay.counterfactual.CounterfactualResult.VariantResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists and reads {@code counterfactual_runs} (one row per completed counterfactual replay). */
@Repository
public class CounterfactualRunRepository {

  private static final TypeReference<List<VariantResult>> VARIANT_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC + Jackson. */
  public CounterfactualRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /**
   * Inserts one completed counterfactual run; returns the generated run id (the {@code resultRef}).
   * Replace-on-rerun (mirroring {@code RunRepository.insert}): a crash-recovery re-execution replaces
   * the job's prior run instead of duplicating; {@code uq_counterfactual_runs_job} is the backstop.
   */
  public UUID insert(
      UUID jobId,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      int entryCount,
      List<VariantResult> variants,
      String createdBy) {
    jdbc.update("DELETE FROM counterfactual_runs WHERE job_id = ?", jobId);
    return jdbc.queryForObject(
        """
        INSERT INTO counterfactual_runs
          (job_id, window_from, window_to, entry_count, variant_count, variants, created_by)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
        RETURNING id
        """,
        (rs, n) -> UUID.fromString(rs.getString("id")),
        jobId,
        windowFrom,
        windowTo,
        entryCount,
        variants.size(),
        json(variants),
        createdBy);
  }

  /** The typed result for one run, or empty when the run id is unknown. */
  public Optional<CounterfactualResult> findResult(UUID runId) {
    return jdbc
        .query(
            "SELECT id, job_id, window_from, window_to, entry_count, variant_count, variants, "
                + "completed_at FROM counterfactual_runs WHERE id=?",
            (rs, n) ->
                new CounterfactualResult(
                    rs.getString("id"),
                    rs.getString("job_id"),
                    offset(rs.getObject("window_from", OffsetDateTime.class)),
                    offset(rs.getObject("window_to", OffsetDateTime.class)),
                    rs.getInt("entry_count"),
                    rs.getInt("variant_count"),
                    parseVariants(rs.getString("variants")),
                    offset(rs.getObject("completed_at", OffsetDateTime.class))),
            runId)
        .stream()
        .findFirst();
  }

  /** The run id produced by a job (for the {@code resultRef} on the job-status payload). */
  public Optional<UUID> findRunIdByJobId(UUID jobId) {
    return jdbc
        .query(
            "SELECT id FROM counterfactual_runs WHERE job_id=?",
            (rs, n) -> UUID.fromString(rs.getString("id")),
            jobId)
        .stream()
        .findFirst();
  }

  private static String offset(OffsetDateTime ts) {
    return ts == null ? null : ts.toString();
  }

  private List<VariantResult> parseVariants(String raw) {
    try {
      return objectMapper.readValue(raw, VARIANT_LIST);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("corrupt counterfactual variants JSONB", e);
    }
  }

  private String json(List<VariantResult> variants) {
    try {
      return objectMapper.writeValueAsString(variants);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("counterfactual variants not serializable", e);
    }
  }
}
