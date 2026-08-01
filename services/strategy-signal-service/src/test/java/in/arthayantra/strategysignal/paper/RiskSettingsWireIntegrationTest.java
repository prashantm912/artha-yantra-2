package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end wire guard for the D3 conversion of {@code GET/PUT /api/v1/risk/settings} (Map →
 * {@code RiskViews.RiskSettings}).
 *
 * <p>{@link MapReturnConversionWireTest} pins the same key sets with mocks; this exists because that
 * one CANNOT catch the class of bug the conversion actually introduced risk of — {@code
 * RiskSettingsRepository.auditTail} stopped being a {@code jdbc.queryForList} and became a hand-written
 * {@code RowMapper} reading columns BY NAME, so a mistyped column label throws only against a real
 * schema. This drives the real controller, the real repository and the real Boot ObjectMapper against
 * the Testcontainers DB.
 *
 * <p>ITs share the singleton DB with no per-method cleanup, so every method writes under its own
 * generated book name.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class RiskSettingsWireIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  private static String uniqueBook() {
    return "d3wire-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static List<String> keysOf(JsonNode node) {
    List<String> keys = new ArrayList<>();
    node.fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  private JsonNode putLimit(String book) throws Exception {
    String body =
        """
        {"book":"%s","key":"kill_switch","value":{"enabled":true}}
        """
            .formatted(book);
    String json =
        mvc.perform(put("/api/v1/risk/settings").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(json);
  }

  @Test
  void putThenGetRenderTheSameThreeKeyEnvelope() throws Exception {
    String book = uniqueBook();
    JsonNode afterPut = putLimit(book);
    assertThat(keysOf(afterPut)).containsExactly("book", "items", "audit");
    assertThat(afterPut.get("book").asText()).isEqualTo(book);

    String json =
        mvc.perform(get("/api/v1/risk/settings").param("book", book))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode afterGet = objectMapper.readTree(json);
    assertThat(keysOf(afterGet)).containsExactly("book", "items", "audit");
  }

  @Test
  void settingRowsKeepTheirThreeCamelCaseKeys() throws Exception {
    String book = uniqueBook();
    JsonNode items = putLimit(book).get("items");
    assertThat(items).isNotEmpty();
    assertThat(keysOf(items.get(0))).containsExactly("key", "value", "updatedAt");
    assertThat(items.get(0).get("value").get("enabled").asBoolean()).isTrue();
  }

  /**
   * The audit row is the one the RowMapper rewrote. {@code created_at} is SNAKE_CASE because the old
   * value was a {@code queryForList} column map — renaming it to {@code createdAt} would be a silent
   * wire break, so this asserts the real serialized key against a real inserted row.
   */
  @Test
  void auditRowsKeepSnakeCaseCreatedAtAndAllFourKeys() throws Exception {
    String book = uniqueBook();
    JsonNode audit = putLimit(book).get("audit");
    assertThat(audit).isNotEmpty();
    JsonNode row = audit.get(0);
    assertThat(keysOf(row)).containsExactly("key", "action", "detail", "created_at");
    assertThat(row.get("key").asText()).isEqualTo("kill_switch");
    assertThat(row.get("action").asText()).isEqualTo("UPDATE");
    assertThat(row.get("created_at").isNull()).as("NOT NULL in V006").isFalse();
  }

  /** An absent {@code book} still falls back to the scalper book. */
  @Test
  void absentBookDefaultsToScalper() throws Exception {
    String json =
        mvc.perform(get("/api/v1/risk/settings"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(json).get("book").asText()).isEqualTo("scalper");
  }
}
