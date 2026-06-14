package in.arthayantra.strategysignal.journal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.List;
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
 * Phase 44A IT (mock profile): the journal CRUD lifecycle, same-schema FK validation (unknown
 * signal_id → 422), free entries, filters, and FK-free soft backtest references. ITs share the DB —
 * each method uses a unique tag for isolation.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class JournalIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void crudLifecycleForAFreeEntry() throws Exception {
    String tag = "fomo-" + UUID.randomUUID();
    String body =
        mockMvc
            .perform(
                post("/api/v1/journal")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(Map.of("note", "rushed the entry", "tags", List.of(tag), "disciplineRating", 2))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = objectMapper.readTree(body).get("id").asLong();

    mockMvc.perform(get("/api/v1/journal/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.note").value("rushed the entry"));
    mockMvc
        .perform(
            put("/api/v1/journal/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", "edited", "tags", List.of(tag), "emotionRating", 4))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").value("edited"));
    mockMvc.perform(delete("/api/v1/journal/" + id)).andExpect(status().isNoContent());
    mockMvc.perform(get("/api/v1/journal/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void unknownSignalIdIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/journal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("signalId", 999_999_999, "note", "x"))))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void softBacktestReferencesAreStoredWithoutACrossSchemaFk() throws Exception {
    String tag = "bt-" + UUID.randomUUID();
    // a backtest_run_id that exists in NO schema — accepted because it is a plain soft reference
    mockMvc
        .perform(
            post("/api/v1/journal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "backtestRunId", UUID.randomUUID().toString(),
                            "backtestTradeId", 7,
                            "note", "good exit",
                            "tags", List.of(tag)))))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/journal").param("tag", tag).param("linkedTo", "backtest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].backtestTradeId").value(7));
  }

  @Test
  void filtersByTagAndLinkedEntity() throws Exception {
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    Long signalId =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               composite_score, score_breakdown)
            VALUES (?, 'NSE', 'JRNL', '1m', 'ENTRY', 'BUY', 0.7, '{}'::jsonb) RETURNING id
            """,
            Long.class,
            versionId);
    String freeTag = "free-" + UUID.randomUUID();
    String linkedTag = "linked-" + UUID.randomUUID();
    create(Map.of("note", "free", "tags", List.of(freeTag)));
    create(Map.of("signalId", signalId, "note", "linked", "tags", List.of(linkedTag)));

    mockMvc
        .perform(get("/api/v1/journal").param("tag", linkedTag).param("linkedTo", "signal"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].signalId").value(signalId));
    mockMvc
        .perform(get("/api/v1/journal").param("tag", freeTag).param("linkedTo", "none"))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  private void create(Map<String, ?> body) throws Exception {
    mockMvc
        .perform(post("/api/v1/journal").contentType(MediaType.APPLICATION_JSON).content(json(body)))
        .andExpect(status().isCreated());
  }

  private String json(Map<String, ?> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }
}
