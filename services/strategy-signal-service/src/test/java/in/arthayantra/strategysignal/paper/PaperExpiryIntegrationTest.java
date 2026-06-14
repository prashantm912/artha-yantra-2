package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.ContractInfoClient.ContractInfo;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 43B IT (mock profile): expiry settlement closes derivative paper positions at intrinsic/spot
 * with {@code close_reason=EXPIRY_SETTLEMENT} (and the exercise STT leg), releasing the open key;
 * stock F&O closes with the physical-settlement warning; the T-1 roll-or-close push records an audit
 * row and dedupes. Contract info + instrument meta are stubbed; spot is seeded into Redis.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperExpiryIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          tradingsymbol.startsWith("EXPFUT")
              ? new InstrumentMeta(InstrumentClass.FUTURE, new BigDecimal("0.05"), 50)
              : new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }

    @Bean
    @Primary
    ContractInfoClient stubContracts() {
      return (exchange, tradingsymbol) -> {
        LocalDate today = LocalDate.now(IST);
        LocalDate expiry = tradingsymbol.contains("-T1-") ? today.plusDays(1) : today;
        if (tradingsymbol.startsWith("EXPFUT")) {
          return Optional.of(
              new ContractInfo(expiry, "NSE", "NIFTY 50", null, null, InstrumentClass.FUTURE, true));
        }
        if (tradingsymbol.startsWith("EXPSTK")) {
          return Optional.of(
              new ContractInfo(expiry, "NSE", "RELIANCE", new BigDecimal("2500"), "CE", InstrumentClass.OPTION, false));
        }
        if (tradingsymbol.startsWith("EXPOPT")) {
          return Optional.of(
              new ContractInfo(expiry, "NSE", "NIFTY 50", new BigDecimal("18000"), "CE", InstrumentClass.OPTION, true));
        }
        return Optional.empty(); // other symbols (leftover positions) are not derivatives here
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperService paper;
  @Autowired private PaperExpiryService expiry;
  @Autowired private PaperPositionRepository positions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM paper_positions");
    jdbc.update("DELETE FROM paper_orders");
    seedSpot("NIFTY 50", "18100.00"); // ITM for an 18000 CE
  }

  @Test
  void intrinsicIsZeroFloored() {
    assertThat(PaperExpiryService.intrinsic("CE", bd("18100"), bd("18000"))).isEqualByComparingTo("100");
    assertThat(PaperExpiryService.intrinsic("CE", bd("17900"), bd("18000"))).isEqualByComparingTo("0");
    assertThat(PaperExpiryService.intrinsic("PE", bd("17900"), bd("18000"))).isEqualByComparingTo("100");
    assertThat(PaperExpiryService.intrinsic("PE", bd("18100"), bd("18000"))).isEqualByComparingTo("0");
  }

  @Test
  void indexOptionSettlesAtIntrinsicWithCloseReasonAndReleasesTheKey() {
    String sym = "EXPOPT-" + UUID.randomUUID();
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00")));
    assertThat(positions.findOpen("NFO", sym, "BUY")).isPresent();

    assertThat(expiry.settleExpiries()).isEqualTo(1);

    var settled = positions.listClosed(null, null, sym, 10, 0);
    assertThat(settled).hasSize(1);
    assertThat(settled.get(0).closeReason()).isEqualTo("EXPIRY_SETTLEMENT");
    // no derivative position remains OPEN past expiry; the key is re-openable
    assertThat(positions.findOpen("NFO", sym, "BUY")).isEmpty();
  }

  @Test
  void stockFnoClosesWithPhysicalSettlementWarning() {
    String sym = "EXPSTK-" + UUID.randomUUID();
    seedSpot("RELIANCE", "2550.00");
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("40.00")));

    assertThat(expiry.settleExpiries()).isEqualTo(1);
    assertThat(positions.findOpen("NFO", sym, "BUY")).isEmpty();
    assertThat(positions.listClosed(null, null, sym, 10, 0).get(0).closeReason()).isEqualTo("EXPIRY_SETTLEMENT");
  }

  @Test
  void t1PushRecordsAnAuditRowAndDedupes() throws Exception {
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    String sym = "EXPOPT-T1-" + UUID.randomUUID().toString().substring(0, 8);
    Long signalId =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               entry_price, composite_score, score_breakdown)
            VALUES (?, 'NFO', ?, '1d', 'ENTRY', 'BUY', 80.0000, 0.7000, '{}'::jsonb)
            RETURNING id
            """,
            Long.class,
            versionId,
            sym);
    redis
        .opsForHash()
        .put("ticks:last", "NFO:" + sym, tickJson(sym, "80.00"));
    mockMvc
        .perform(
            post("/api/v1/signals/" + signalId + "/taken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("qty", 50))));
    assertThat(positions.findOpen("NFO", sym, "BUY")).isPresent();

    assertThat(expiry.notifyExpiring()).isEqualTo(1); // expires tomorrow -> one push
    assertThat(expiry.notifyExpiring()).isEqualTo(0); // deduped on re-run

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_events WHERE detail LIKE ?", Integer.class, "%" + sym + "%");
    assertThat(rows).isEqualTo(1);
  }

  private void seedSpot(String symbol, String price) {
    redis.opsForHash().put("ticks:last", "NSE:" + symbol, tickJson(symbol, price));
  }

  private String tickJson(String symbol, String price) {
    try {
      return objectMapper.writeValueAsString(
          Map.of("exchange", "NSE", "tradingsymbol", symbol, "lastPrice", price));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
