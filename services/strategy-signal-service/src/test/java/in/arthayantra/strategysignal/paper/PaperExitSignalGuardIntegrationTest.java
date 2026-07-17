package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** Guards paper-entry boundaries against signal rows that are not ENTRY propositions. */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperExitSignalGuardIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String SWING_BOOK = "minervini";
  private static final String MANUAL_BOOK = "manual";

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RiskSettingsRepository riskSettings;
  @Autowired private SignalRepository signals;

  private final List<RiskSnapshot> riskBefore = new ArrayList<>();
  private final List<String> symbols = new ArrayList<>();

  private record RiskSnapshot(
      String book, String key, RiskSettingsRepository.Setting setting) {}

  @BeforeEach
  void disableEntryRails() {
    riskBefore.clear();
    symbols.clear();
    for (String book : List.of(SWING_BOOK, MANUAL_BOOK)) {
      for (String rail : List.of(
          RiskService.KILL_SWITCH,
          RiskService.MAX_OPEN,
          RiskService.DAILY_LOSS,
          RiskService.DAILY_PROFIT_TARGET,
          RiskService.MAX_DEPLOYMENT_PCT,
          RiskService.HEAT_CAP_PCT)) {
        riskBefore.add(new RiskSnapshot(book, rail, riskSettings.get(book, rail).orElse(null)));
        riskSettings.upsert(book, rail, "{\"enabled\":false}");
      }
    }
  }

  @AfterEach
  void restoreSharedPaperState() {
    for (String symbol : symbols) {
      jdbc.update("DELETE FROM paper_orders WHERE tradingsymbol=?", symbol);
      jdbc.update("DELETE FROM paper_positions WHERE tradingsymbol=?", symbol);
    }
    for (RiskSnapshot snapshot : riskBefore) {
      if (snapshot.setting() == null) {
        jdbc.update(
            "DELETE FROM risk_settings WHERE book=? AND key=?", snapshot.book(), snapshot.key());
      } else {
        jdbc.update(
            "UPDATE risk_settings SET value=?::jsonb, updated_at=? WHERE book=? AND key=?",
            snapshot.setting().value().toString(),
            snapshot.setting().updatedAt(),
            snapshot.book(),
            snapshot.key());
      }
    }
  }

  @Test
  void manualTakeOfAnExitSignalIsRefusedAndOpensNoPosition() throws Exception {
    String symbol = uniqueSymbol("EXIT-GUARD-");
    long signalId = insertSwingSignal(symbol, "EXIT", "BUY");

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(signalId, symbol, "BUY", null)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(openPositionCount(symbol)).isZero();
    assertThat(orderCount(symbol)).isZero();
  }

  @Test
  void signalLifecycleTakeOfAnExitSignalIsRefusedAndLeavesItActive() throws Exception {
    String symbol = uniqueSymbol("EXIT-LIFECYCLE-GUARD-");
    long signalId = insertSwingSignal(symbol, "EXIT", "SELL");

    mockMvc
        .perform(
            post("/api/v1/signals/" + signalId + "/taken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qty\":1}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    assertThat(signals.find(signalId).orElseThrow().status()).isEqualTo("ACTIVE");
    assertThat(openPositionCount(symbol)).isZero();
    assertThat(orderCount(symbol)).isZero();
  }

  @Test
  void manualTakeOfAnEntrySignalStillOpens() throws Exception {
    String symbol = uniqueSymbol("ENTRY-GUARD-");
    long signalId = insertSwingSignal(symbol, "ENTRY", "BUY");

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(signalId, symbol, "BUY", null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openPositionCount(symbol)).isEqualTo(1);
  }

  @Test
  void pureManualOrderWithoutASignalStillOpens() throws Exception {
    String symbol = uniqueSymbol("NULL-SIGNAL-GUARD-");

    mockMvc
        .perform(
            post("/api/v1/paper/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(order(null, symbol, "BUY", MANUAL_BOOK)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openPositionCount(symbol)).isEqualTo(1);
  }

  private String uniqueSymbol(String prefix) {
    String symbol = prefix + UUID.randomUUID();
    symbols.add(symbol);
    return symbol;
  }

  private long insertSwingSignal(String symbol, String signalType, String side) {
    UUID versionId = seedSwingVersion();
    OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    return signals.insert(
        versionId,
        "NSE",
        symbol,
        "1m",
        signalType,
        side,
        new BigDecimal("100.00"),
        new BigDecimal("50.00"),
        new BigDecimal("150.00"),
        new BigDecimal("0.80"),
        "{}",
        generatedAt,
        generatedAt.plusHours(1));
  }

  private UUID seedSwingVersion() {
    String suffix = UUID.randomUUID().toString();
    UUID strategyId =
        jdbc.queryForObject(
            "INSERT INTO strategies (slug, name, tags) VALUES (?, ?, ?::text[]) RETURNING id",
            UUID.class,
            "exit-guard-" + suffix,
            "Exit Guard " + suffix,
            "{minervini}");
    return jdbc.queryForObject(
        """
        INSERT INTO strategy_versions
          (strategy_id, version, config_yaml, config, schema_version, checksum, status)
        VALUES (?, '1', '', '{}'::jsonb, '1', ?, 'published') RETURNING id
        """,
        UUID.class,
        strategyId,
        "chk-" + suffix);
  }

  private String order(Long signalId, String symbol, String side, String book) throws Exception {
    Map<String, Object> body = new java.util.HashMap<>();
    if (signalId != null) {
      body.put("signalId", signalId);
    }
    body.put("exchange", "NSE");
    body.put("tradingsymbol", symbol);
    body.put("side", side);
    body.put("qty", 1);
    body.put("price", "100.00");
    if (book != null) {
      body.put("book", book);
    }
    return objectMapper.writeValueAsString(body);
  }

  private int openPositionCount(String symbol) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE tradingsymbol=? AND status='OPEN'",
            Integer.class,
            symbol);
    return count == null ? 0 : count;
  }

  private int orderCount(String symbol) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_orders WHERE tradingsymbol=?",
            Integer.class,
            symbol);
    return count == null ? 0 : count;
  }
}
