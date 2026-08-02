package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 43 IT (mock profile): the paper ledger fills through the shared engine JAR (fill-audit
 * stamped, id {@code ltp_slippage/v1}), averages onto the §F.6 partial-unique open key, realizes
 * P&amp;L at close, and opens a position from a TAKEN signal. The instrument-meta lookup is stubbed
 * (no market-data running in this service's IT); the last tick is seeded into Redis directly.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperLedgerIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          tradingsymbol.startsWith("TESTOPT")
              ? new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50)
              : new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperPositionRepository positions;
  @Autowired private PaperService paper;
  @Autowired private PaperBracketEvaluator brackets;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private PaperOrderRepository orders;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void stopLossBracketAutoClosesOnBreach() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    // long with SL 90 / TP 120, filled ~100 — the bracket levels are stored + surfaced
    String body =
        mockMvc
            .perform(
                post("/api/v1/paper/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "exchange", "NFO", "tradingsymbol", sym, "side", "BUY", "qty", 50,
                                "price", "100.00", "stopLoss", "90.00", "takeProfit", "120.00"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stopLoss").value("90.0000"))
            .andExpect(jsonPath("$.takeProfit").value("120.0000"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = objectMapper.readTree(body).get("id").asLong();

    // the LTP drops below the stop → the evaluator auto-closes with close_reason STOP_LOSS
    redis
        .opsForHash()
        .put(
            "ticks:last",
            "NFO:" + sym,
            json(Map.of("exchange", "NFO", "tradingsymbol", sym, "lastPrice", "88.00")));
    assertThat(brackets.evaluate()).isEqualTo(1);

    assertThat(positions.find(id)).get().extracting(p -> p.status()).isEqualTo("CLOSED");
    assertThat(positions.find(id)).get().extracting(p -> p.closeReason()).isEqualTo("STOP_LOSS");
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isEmpty();
  }

  @Test
  void openAveragesOntoTheOpenKeyAndStampsTheFillAudit() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    order(sym, "SELL", 50, "100.00")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.qty").value(50))
        .andExpect(jsonPath("$.avgEntryPrice").value("99.9500"));

    Map<String, Object> auditRow =
        jdbc.queryForMap(
            "SELECT fill_simulator, slippage_applied FROM paper_orders WHERE tradingsymbol=? ORDER BY id DESC LIMIT 1",
            sym);
    assertThat(auditRow.get("fill_simulator")).isEqualTo("ltp_slippage/v1");
    assertThat(auditRow.get("slippage_applied").toString()).isEqualTo("0.0500");

    // a second open on the same key averages into the SAME open position (one row, qty 100)
    order(sym, "SELL", 50, "100.00").andExpect(status().isCreated()).andExpect(jsonPath("$.qty").value(100));
    assertThat(positions.findOpen("manual", "NFO", sym, "SELL")).isPresent();
    assertThat(positions.listOpen().stream().filter(p -> p.tradingsymbol().equals(sym)).count())
        .isEqualTo(1);
  }

  @Test
  void closeRealizesPnlAndReleasesTheOpenKey() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    String body = order(sym, "SELL", 50, "120.00").andReturn().getResponse().getContentAsString();
    long id = objectMapper.readTree(body).get("id").asLong();

    mockMvc
        .perform(
            post("/api/v1/paper/positions/" + id + "/close")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("price", "100.00"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.realizedPnl").exists());

    // a short sold at ~120 and bought back at ~100 is profitable, and the position is CLOSED
    assertThat(positions.find(id)).get().extracting(p -> p.status()).isEqualTo("CLOSED");
    assertThat(positions.find(id)).get().extracting(p -> p.realizedPnl().signum()).isEqualTo(1);
    assertThat(positions.findOpen("manual", "NFO", sym, "SELL")).isEmpty(); // key released

    mockMvc
        .perform(get("/api/v1/paper/trades"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.limit").exists())
        .andExpect(jsonPath("$.offset").exists());

    // /positions is a bare single-key envelope; the item shape is PositionDto, already typed.
    mockMvc
        .perform(get("/api/v1/paper/positions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

    // The D3 retyping's wire proof for /pnl, the one converted paper body that actually NESTS: the
    // envelope, the curve points and every summary key must survive the Map -> record change. A
    // closed position exists by now, so `points` is non-empty and winRate/expectancy are non-null.
    // Member ORDER and the zero-trade null case are pinned separately in
    // PaperViewsSerializationTest — jsonPath cannot see either.
    mockMvc
        .perform(get("/api/v1/paper/pnl"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points").isArray())
        .andExpect(jsonPath("$.points[0].date").exists())
        .andExpect(jsonPath("$.points[0].equity").exists())
        .andExpect(jsonPath("$.summary.realizedTotal").exists())
        .andExpect(jsonPath("$.summary.trades").exists())
        .andExpect(jsonPath("$.summary.winRate").exists())
        .andExpect(jsonPath("$.summary.expectancy").exists());
  }

  @Test
  void partialUniqueOpenKeyRejectsASecondRawOpen() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    positions.insertOpen("manual", "NFO", sym, "SELL", 50, new BigDecimal("99.95"), null, null);
    assertThatThrownBy(() -> positions.insertOpen("manual", "NFO", sym, "SELL", 25, new BigDecimal("99.90"), null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /**
   * V055: a CLOSED paper position must carry its {@code closed_at}. Until V055 this held only by
   * convention — {@link PaperPositionRepository#close} is the sole writer of {@code status='CLOSED'}
   * and stamps {@code closed_at=now()} in the same atomic UPDATE — so a second closer could have
   * falsified it silently, and every reader that windows on {@code closed_at} (listClosed,
   * PortfolioReader, GraduationService, PaperService.equity) would have dropped or misdated the row
   * rather than erroring. It also backs the NON-nullable {@code PaperService.TradeDto.closedAt} in
   * the published OpenAPI contract. Both violating write shapes are asserted, plus the legitimate
   * close, so a constraint that is merely too TIGHT fails here too.
   */
  @Test
  void closedPositionWithoutClosedAtIsRejectedByTheDatabase() {
    String sym = "TESTOPT-" + UUID.randomUUID();
    String book = "ck-closed-at-" + UUID.randomUUID().toString().substring(0, 8);

    // (1) INSERT a CLOSED row that never stamped closed_at.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO paper_positions (book, exchange, tradingsymbol, side, qty,"
                        + " avg_entry_price, status, opened_at)"
                        + " VALUES (?, 'NFO', ?, 'BUY', 50, 100, 'CLOSED', now())",
                    book, sym))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_paper_positions_closed_at_present");

    // (2) UPDATE an OPEN row to CLOSED without stamping closed_at — the shape a future second
    // closer would actually take.
    long id = positions.insertOpen(book, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE paper_positions SET status='CLOSED' WHERE id=?", id))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_paper_positions_closed_at_present");

    // (3) the real close() — status and closed_at set together — must still be accepted.
    assertThat(positions.close(id, new BigDecimal("5.00"), "MANUAL")).isEqualTo(1);
    assertThat(positions.find(id).orElseThrow().closedAt()).isNotNull();
  }

  @Test
  void takenSignalWithQtyOpensAPaperPosition() throws Exception {
    UUID versionId = jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
    String sym = "PAPEREQ" + UUID.randomUUID().toString().substring(0, 8);
    OffsetDateTime generatedAt =
        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
    Long signalId =
        jdbc.queryForObject(
            """
            INSERT INTO signals
              (strategy_version_id, exchange, tradingsymbol, "interval", signal_type, side,
               entry_price, composite_score, score_breakdown, generated_at)
            VALUES (?, 'NSE', ?, '1m', 'ENTRY', 'BUY', 100.0000, 0.7000, '{}'::jsonb, ?)
            RETURNING id
            """,
            Long.class,
            versionId,
            sym,
            generatedAt);
    redis
        .opsForHash()
        .put(
            "ticks:last",
            "NSE:" + sym,
            objectMapper.writeValueAsString(
                Map.of("exchange", "NSE", "tradingsymbol", sym, "lastPrice", "105.00")));

    long timerCountBefore =
        meterRegistry.find("ay_signal_to_fill_seconds").timer() == null
            ? 0
            : meterRegistry.find("ay_signal_to_fill_seconds").timer().count();

    mockMvc
        .perform(
            post("/api/v1/signals/" + signalId + "/taken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("qty", 10))))
        .andExpect(status().isOk());

    assertThat(positions.findOpen("other", "NSE", sym, "BUY")).isPresent();
    Map<String, Object> latencyStamp =
        jdbc.queryForMap(
            """
            SELECT s.generated_at, o.tick_to_fill_ms,
                   (floor(extract(epoch FROM o.filled_at) * 1000)
                    - floor(extract(epoch FROM s.generated_at) * 1000))::bigint AS expected_latency_ms
            FROM paper_orders o JOIN signals s ON s.id = o.signal_id
            WHERE o.signal_id = ?
            """,
            signalId);
    assertThat(((java.sql.Timestamp) latencyStamp.get("generated_at")).toInstant())
        .as("paper latency instrumentation does not rewrite signals.generated_at")
        .isEqualTo(generatedAt.toInstant());
    assertThat(latencyStamp.get("tick_to_fill_ms"))
        .isEqualTo(latencyStamp.get("expected_latency_ms"));
    assertThat(meterRegistry.find("ay_signal_to_fill_seconds").timer())
        .isNotNull()
        .extracting(timer -> timer.count())
        .isEqualTo(timerCountBefore + 1);
    assertThat(
            java.util.Arrays.stream(
                    meterRegistry
                        .find("ay_signal_to_fill_seconds")
                        .timer()
                        .takeSnapshot()
                        .percentileValues())
                .mapToDouble(value -> value.percentile())
                .toArray())
        .contains(0.5, 0.95);
  }

  @Test
  void rolledBackFillDoesNotIncrementTheSignalToFillTimer() {
    String sym = "PAPERROLLBACK" + UUID.randomUUID().toString().substring(0, 8);
    long timerCountBefore =
        meterRegistry.find("ay_signal_to_fill_seconds").timer().count();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      orders.insertFilled(
                          "other",
                          null,
                          "NSE",
                          sym,
                          "BUY",
                          1,
                          new BigDecimal("100.00"),
                          "ltp_slippage/v1",
                          BigDecimal.ZERO,
                          null,
                          null,
                          null,
                          "SIGNAL_ENTRY",
                          null,
                          OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
                      throw new IllegalStateException("force rollback after fill insert");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM paper_orders WHERE tradingsymbol = ?", Long.class, sym))
        .isZero();
    assertThat(meterRegistry.find("ay_signal_to_fill_seconds").timer().count())
        .isEqualTo(timerCountBefore);
  }

  @Test
  void committedFillIsNotReportedAsFailedWhenTheTimerThrows() {
    String sym = "PAPERMETERFAIL" + UUID.randomUUID().toString().substring(0, 8);
    Timer originalTimer =
        (Timer) ReflectionTestUtils.getField(orders, "signalToFillTimer");
    Timer throwingTimer = mock(Timer.class);
    doThrow(new IllegalStateException("meter unavailable"))
        .when(throwingTimer)
        .record(anyLong(), eq(TimeUnit.MILLISECONDS));
    ReflectionTestUtils.setField(orders, "signalToFillTimer", throwingTimer);

    try {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);
      assertThatCode(
              () ->
                  tx.executeWithoutResult(
                      status ->
                          orders.insertFilled(
                              "other",
                              null,
                              "NSE",
                              sym,
                              "BUY",
                              1,
                              new BigDecimal("100.00"),
                              "ltp_slippage/v1",
                              BigDecimal.ZERO,
                              null,
                              null,
                              null,
                              "SIGNAL_ENTRY",
                              null,
                              OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))))
          .doesNotThrowAnyException();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM paper_orders WHERE tradingsymbol = ?", Long.class, sym))
          .isEqualTo(1L);
    } finally {
      ReflectionTestUtils.setField(orders, "signalToFillTimer", originalTimer);
    }
  }

  @Test
  void aStampedOrderChargesThePositionToItsSubAccount() {
    // E10: openOrder threads OrderRequest.subaccountIdx through to insertOpen's subaccount_idx column.
    String sym = "TESTOPT-" + UUID.randomUUID();
    paper.openOrder(
        new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("100.00"), null, null, 2));
    Integer idx =
        jdbc.queryForObject(
            "SELECT subaccount_idx FROM paper_positions WHERE tradingsymbol=? AND status='OPEN'",
            Integer.class,
            sym);
    assertThat(idx).isEqualTo(2);
  }

  @Test
  void resetRequiresConfirmThenWipesTheLedger() throws Exception {
    String sym = "TESTOPT-" + UUID.randomUUID();
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "SELL", 10, new BigDecimal("100.00"), null, null));

    mockMvc
        .perform(
            post("/api/v1/paper/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("confirm", false))))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/paper/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("confirm", true))))
        .andExpect(status().isNoContent());

    assertThat(positions.listOpen()).isEmpty();
  }

  private org.springframework.test.web.servlet.ResultActions order(
      String sym, String side, int qty, String price) throws Exception {
    return mockMvc.perform(
        post("/api/v1/paper/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json(
                    Map.of(
                        "exchange", "NFO",
                        "tradingsymbol", sym,
                        "side", side,
                        "qty", qty,
                        "price", price))));
  }

  private String json(Map<String, ?> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }
}
