package in.arthayantra.strategysignal.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * E10 auto-journal IT (mock profile): closing a paper position fires {@link
 * in.arthayantra.strategysignal.paper.PaperPositionClosed} AFTER_COMMIT, which auto-stubs a linked,
 * {@code auto}-tagged journal entry. The base is non-transactional (real commits) so the listener
 * has fired by the time the close response returns.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class AutoJournalIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void closingAPaperPositionWritesAnAutoTaggedLinkedJournalEntry() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    String body =
        mockMvc
            .perform(
                post("/api/v1/paper/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "exchange", "NFO", "tradingsymbol", sym, "side", "SELL", "qty", 50,
                                "price", "120.00"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = objectMapper.readTree(body).get("id").asLong();

    mockMvc
        .perform(
            post("/api/v1/paper/positions/" + id + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("price", "100.00"))))
        .andExpect(status().isOk());

    // a short sold ~120 bought back ~100 is profitable -> an auto/win entry linked to the position.
    Integer entries =
        jdbc.queryForObject(
            "SELECT count(*) FROM journal_entries WHERE paper_position_id=? AND 'auto' = ANY(tags)",
            Integer.class,
            id);
    assertThat(entries).isEqualTo(1);
    Integer wins =
        jdbc.queryForObject(
            "SELECT count(*) FROM journal_entries WHERE paper_position_id=? AND 'win' = ANY(tags)"
                + " AND discipline_rating IS NULL AND emotion_rating IS NULL",
            Integer.class,
            id);
    assertThat(wins).isEqualTo(1);
  }

  private String json(Map<String, ?> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }
}
