package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Audit V3 tick-freshness guards on the paper exit path (app-platform audit 2026-07-10 §8):
 *
 * <ul>
 *   <li><b>Stale-tick fill</b>: a paper fill that would price off a last tick older than
 *       {@code artha.paper.tick-max-age-seconds} is rejected 422 DATA_STALE — a fill against a
 *       stale LTP is fiction. A fresh tick fills normally.
 *   <li><b>No silent breakeven settle</b>: settling a position with no explicit price AND no live
 *       tick no longer fabricates a 0-P&amp;L exit at {@code avgEntryPrice} — it refuses (422),
 *       leaves the position OPEN, and bumps the {@code ay_paper_settle_refused_total} counter.
 * </ul>
 *
 * <p>A FIXED {@link Clock} is pinned so the seeded tick's age is exact. Dedicated book 'a3tick'
 * (unseeded → ungoverned) with UUID symbols + per-method cleanup, so it never collides with A1's
 * 'manual' book, A2's 'a2val' book, or surefire reruns. Engine disabled — no live components spin up.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperTickFreshnessIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String BOOK = "a3tick";
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  /** The pinned "now": the seeded tick timestamps are computed relative to this instant. */
  private static final Instant FIXED_NOW = Instant.parse("2026-07-11T05:00:00Z");

  @TestConfiguration
  static class Stubs {
    /** Pin the clock so LastTickReader's tick-age is deterministic (age = FIXED_NOW − tick timestamp). */
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    }

    /** Every symbol resolves as plain equity (lot 1) so the fill prices without a real instrument master. */
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) -> new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StringRedisTemplate redis;
  @Autowired private MeterRegistry meters;

  @BeforeEach
  @AfterEach
  void reset() {
    jdbc.update("DELETE FROM paper_orders WHERE book=?", BOOK);
    jdbc.update("DELETE FROM paper_positions WHERE book=?", BOOK);
    jdbc.update("DELETE FROM risk_settings WHERE book=?", BOOK);
    jdbc.update("DELETE FROM risk_audit WHERE book=?", BOOK);
  }

  // ─── stale-tick fill rejection ───────────────────────────────────────────────────────────────────

  @Test
  void fillPricedOffAStaleTickIsRejectedDataStale() throws Exception {
    String sym = "EQ-" + UUID.randomUUID();
    // 20 minutes old — far beyond the 15s default max age.
    seedTick(sym, "123.45", FIXED_NOW.minusSeconds(20 * 60));

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(orderNoPrice(sym)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_STALE"))
        .andExpect(jsonPath("$.details.tickAgeSeconds").value(1200));

    assertThat(openCount(sym)).isZero(); // rejected before any fill
    clearTick(sym);
  }

  @Test
  void fillPricedOffAFreshTickSucceeds() throws Exception {
    String sym = "EQ-" + UUID.randomUUID();
    seedTick(sym, "123.45", FIXED_NOW.minusSeconds(1)); // 1s old — fresh

    mockMvc
        .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(orderNoPrice(sym)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(openCount(sym)).isEqualTo(1);
    clearTick(sym);
  }

  // ─── no silent breakeven settle ──────────────────────────────────────────────────────────────────

  @Test
  void settleWithNoPriceRefusesInsteadOfSettlingAtBreakeven() throws Exception {
    String sym = "EQ-" + UUID.randomUUID(); // never seeded → no tick in Redis
    double refusedBefore = meters.counter("ay_paper_settle_refused_total").count();

    // Open at an EXPLICIT price (no tick needed to fill).
    MvcResult opened =
        mockMvc
            .perform(post("/api/v1/paper/orders").contentType(MediaType.APPLICATION_JSON).content(orderWithPrice(sym, "100.00")))
            .andExpect(status().isCreated())
            .andReturn();
    long id = objectMapper.readTree(opened.getResponse().getContentAsString()).get("id").asLong();

    // Close with NO price and NO live tick → the old code booked a breakeven exit at avgEntryPrice.
    // Now it must refuse: 422 DATA_STALE, and the position stays OPEN.
    mockMvc
        .perform(post("/api/v1/paper/positions/" + id + "/close").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_STALE"));

    assertThat(statusOf(id)).isEqualTo("OPEN"); // NOT closed at breakeven
    assertThat(closedCount(sym)).isZero();
    assertThat(realizedOf(id)).isEqualByComparingTo(BigDecimal.ZERO); // untouched
    assertThat(exitOrderCount(sym)).isEqualTo(1); // only the entry fill — no fabricated exit leg
    assertThat(meters.counter("ay_paper_settle_refused_total").count()).isGreaterThan(refusedBefore);
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────────────────────────

  private void seedTick(String sym, String lastPrice, Instant at) {
    String iso = OffsetDateTime.ofInstant(at, IST).toString();
    String json = "{\"lastPrice\":\"" + lastPrice + "\",\"timestamp\":\"" + iso + "\"}";
    redis.opsForHash().put("ticks:last", "NSE:" + sym, json);
  }

  private void clearTick(String sym) {
    redis.opsForHash().delete("ticks:last", "NSE:" + sym);
  }

  private String orderNoPrice(String sym) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("exchange", "NSE");
    body.put("tradingsymbol", sym);
    body.put("side", "BUY");
    body.put("qty", 50);
    body.put("book", BOOK);
    return objectMapper.writeValueAsString(body); // no "price" key → null → falls to the last tick
  }

  private String orderWithPrice(String sym, String price) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("exchange", "NSE");
    body.put("tradingsymbol", sym);
    body.put("side", "BUY");
    body.put("qty", 50);
    body.put("price", price);
    body.put("book", BOOK);
    return objectMapper.writeValueAsString(body);
  }

  private int openCount(String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='OPEN'",
            Integer.class, BOOK, sym);
    return c == null ? 0 : c;
  }

  private int closedCount(String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_positions WHERE book=? AND tradingsymbol=? AND status='CLOSED'",
            Integer.class, BOOK, sym);
    return c == null ? 0 : c;
  }

  private int exitOrderCount(String sym) {
    Integer c =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_orders WHERE book=? AND tradingsymbol=?", Integer.class, BOOK, sym);
    return c == null ? 0 : c;
  }

  private String statusOf(long id) {
    return jdbc.queryForObject("SELECT status FROM paper_positions WHERE id=?", String.class, id);
  }

  private BigDecimal realizedOf(long id) {
    return jdbc.queryForObject("SELECT realized_pnl FROM paper_positions WHERE id=?", BigDecimal.class, id);
  }
}
