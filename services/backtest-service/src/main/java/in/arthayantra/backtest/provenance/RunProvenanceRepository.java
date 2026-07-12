package in.arthayantra.backtest.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the provenance / comparability projection of a {@code backtest_runs} row (roadmap #22a/#30) —
 * the engine identity + reproducibility legs + the V015 content-hash / dataset-epoch / evidence-policy
 * columns + the config checksum and cost class lifted from the metrics JSONB. Read-only; distinct from
 * {@code RunRepository.findResult} (which serves the FE results payload).
 */
@Repository
public class RunProvenanceRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  /** Wires JDBC + Jackson (for the metrics-JSONB config checksum / cost class). */
  public RunProvenanceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  /** The provenance/comparability fields of one run (internal projection). */
  public record RunRow(
      String engineSha,
      String engineImage,
      String configHash,
      String dataHash,
      String contentHash,
      Long datasetEpoch,
      String evidencePolicy,
      String universeChecksum,
      String premiumSource,
      String costClass,
      boolean premiumContentUnverified,
      String exchange,
      String tradingsymbol,
      OffsetDateTime startTs,
      OffsetDateTime endTs) {}

  /** The row, or empty when the run id is unknown. */
  public Optional<RunRow> findRow(UUID runId) {
    return jdbc
        .query(
            "SELECT engine_sha, engine_image, data_hash, content_hash, dataset_epoch, "
                + "evidence_policy, universe_checksum, premium_source, metrics, exchange, "
                + "tradingsymbol, start_ts, end_ts FROM backtest_runs WHERE id=?",
            (rs, n) -> {
              JsonNode metrics = parse(rs.getString("metrics"));
              Long epoch = rs.getObject("dataset_epoch", Long.class);
              return new RunRow(
                  rs.getString("engine_sha"),
                  rs.getString("engine_image"),
                  text(metrics, "strategyChecksum"),
                  rs.getString("data_hash"),
                  rs.getString("content_hash"),
                  epoch,
                  rs.getString("evidence_policy"),
                  rs.getString("universe_checksum"),
                  rs.getString("premium_source"),
                  text(metrics, "costClass"),
                  // review F2: runner-stamped only when true — absent reads false (verified/candle).
                  metrics != null && metrics.path("premiumContentUnverified").asBoolean(false),
                  rs.getString("exchange"),
                  rs.getString("tradingsymbol"),
                  rs.getObject("start_ts", OffsetDateTime.class),
                  rs.getObject("end_ts", OffsetDateTime.class));
            },
            runId)
        .stream()
        .findFirst();
  }

  private static String text(JsonNode metrics, String field) {
    if (metrics == null) {
      return null;
    }
    JsonNode node = metrics.get(field);
    return node == null || node.isNull() ? null : node.asText();
  }

  private JsonNode parse(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return objectMapper.readTree(raw);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("corrupt run metrics JSONB", e);
    }
  }
}
