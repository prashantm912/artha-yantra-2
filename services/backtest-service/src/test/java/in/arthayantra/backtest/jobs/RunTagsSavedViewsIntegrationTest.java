package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.common.web.error.ErrorCodes;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** D4 P2-2: run annotations + owner-scoped saved filter views over the real V020 schema. */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.backtest.worker-pool-enabled=false"
    })
@AutoConfigureMockMvc
class RunTagsSavedViewsIntegrationTest extends BacktestIntegrationTestBase {

  private static final String STRATEGY_ID = "33333333-3333-3333-3333-333333333333";

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void stubVersion() {
    when(versions.resolve(eq(STRATEGY_ID), any()))
        .thenReturn(
            new ResolvedVersion(
                UUID.fromString(STRATEGY_ID),
                UUID.randomUUID(),
                "1.0.0",
                "checksum-d4-p2-2",
                objectMapper.createObjectNode()));
  }

  @Test
  void submitNormalizesAnnotationsAndListFiltersByTag() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String alpha = "a-" + suffix;
    String beta = "b-" + suffix;
    ObjectNode request = runRequest();
    request.putArray("tags").add("  " + alpha + "  ").add("").add(beta).add(alpha);
    request.put("note", "first research note");

    String jobId = submit(request);

    JsonNode items = listByTag(alpha).path("items");
    JsonNode item = findJob(items, jobId);
    assertThat(item).isNotNull();
    assertThat(item.path("tags")).isEqualTo(objectMapper.valueToTree(java.util.List.of(alpha, beta)));
    assertThat(item.path("note").asText()).isEqualTo("first research note");
    assertThat(items).hasSize(1);
  }

  @Test
  void patchReplacesAnnotationsWithoutRewritingSubmissionProvenance() throws Exception {
    String jobId = submit(runRequest());
    String before = requestJson(jobId);
    String tag = "e-" + UUID.randomUUID().toString().replace("-", "");
    ObjectNode body = objectMapper.createObjectNode();
    body.putArray("tags").add("  " + tag + "  ").add("   ");
    body.put("note", "edited after submission");

    mockMvc
        .perform(
            patch("/api/v1/backtests/jobs/{jobId}/annotations", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobId))
        .andExpect(jsonPath("$.tags[0]").value(tag))
        .andExpect(jsonPath("$.note").value("edited after submission"));

    assertThat(requestJson(jobId)).isEqualTo(before);
    assertThat(findJob(listByTag(tag).path("items"), jobId)).isNotNull();
  }

  @Test
  void submitAndPatchShareAnnotationLimits() throws Exception {
    ObjectNode tooLongTag = runRequest();
    tooLongTag.putArray("tags").add("x".repeat(41));
    mockMvc
        .perform(
            post("/api/v1/backtests/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(tooLongTag)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED));

    ObjectNode tooManyTags = runRequest();
    var tags = tooManyTags.putArray("tags");
    IntStream.range(0, 21).forEach(i -> tags.add("tag-" + i));
    mockMvc
        .perform(
            post("/api/v1/backtests/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(tooManyTags)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED));

    String jobId = submit(runRequest());
    ObjectNode longNote = objectMapper.createObjectNode().put("note", "n".repeat(2001));
    longNote.putArray("tags");
    mockMvc
        .perform(
            patch("/api/v1/backtests/jobs/{jobId}/annotations", jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(longNote)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED));
  }

  @Test
  void savedViewsCrudIsKindAndOwnerScopedAndRejectsDuplicateNames() throws Exception {
    String kind = "backtest_jobs_" + UUID.randomUUID();
    String name = "My runs " + UUID.randomUUID();
    ObjectNode filter = objectMapper.createObjectNode().put("status", "completed").put("tag", "alpha");

    JsonNode created = createView(kind, name, filter, 201);
    String id = created.path("id").asText();
    assertThat(created.path("kind").asText()).isEqualTo(kind);
    assertThat(created.path("name").asText()).isEqualTo(name);
    assertThat(created.path("filter")).isEqualTo(filter);
    assertThat(
            jdbc.queryForObject(
                "SELECT owner FROM saved_views WHERE id=?::uuid", String.class, id))
        .isEqualTo("owner");

    assertThat(createView(kind, name, filter, 409).path("code").asText())
        .isEqualTo(ErrorCodes.CONFLICT_WATCHLIST_NAME);
    JsonNode otherKind = createView(kind + "-other", name, filter, 201);

    JsonNode listed = listViews(kind).path("items");
    assertThat(listed).anyMatch(view -> view.path("id").asText().equals(id));
    assertThat(listed)
        .noneMatch(view -> view.path("id").asText().equals(otherKind.path("id").asText()));

    UUID otherOwnerId =
        jdbc.queryForObject(
            "INSERT INTO saved_views (owner, kind, name, filter) VALUES (?, ?, ?, ?::jsonb) RETURNING id",
            UUID.class,
            "other-owner",
            kind,
            "other-owner-" + UUID.randomUUID(),
            "{}");
    mockMvc
        .perform(delete("/api/v1/backtests/saved-views/{id}", otherOwnerId))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM saved_views WHERE id=?", Integer.class, otherOwnerId))
        .isOne();

    mockMvc
        .perform(delete("/api/v1/backtests/saved-views/{id}", id))
        .andExpect(status().isNoContent());
    assertThat(listViews(kind).path("items"))
        .noneMatch(view -> view.path("id").asText().equals(id));
  }

  private String submit(ObjectNode request) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/backtests/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("jobId").asText();
  }

  private ObjectNode runRequest() {
    return objectMapper
        .createObjectNode()
        .put("strategyId", STRATEGY_ID)
        .put("from", "2026-01-05")
        .put("to", "2026-01-09");
  }

  private JsonNode listByTag(String tag) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/backtests/jobs").param("tag", tag).param("limit", "500"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private JsonNode createView(String kind, String name, JsonNode filter, int expectedStatus)
      throws Exception {
    ObjectNode request = objectMapper.createObjectNode().put("kind", kind).put("name", name);
    request.set("filter", filter);
    String body =
        mockMvc
            .perform(
                post("/api/v1/backtests/saved-views")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().is(expectedStatus))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }

  private JsonNode listViews(String kind) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/backtests/saved-views").param("kind", kind))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private String requestJson(String jobId) {
    return jdbc.queryForObject(
        "SELECT request::text FROM jobs WHERE id=?::uuid", String.class, jobId);
  }

  private static JsonNode findJob(JsonNode items, String jobId) {
    for (JsonNode item : items) {
      if (jobId.equals(item.path("jobId").asText())) {
        return item;
      }
    }
    return null;
  }
}
