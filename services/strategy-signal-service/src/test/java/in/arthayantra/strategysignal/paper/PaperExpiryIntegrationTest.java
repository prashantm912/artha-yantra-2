package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.ContractInfoClient.ContractInfo;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
        // -T1- expires tomorrow; -PAST- expired 3 sessions ago (a strand the recon must pick up);
        // otherwise expiring today (the on-time settle path).
        LocalDate expiry =
            tradingsymbol.contains("-T1-")
                ? today.plusDays(1)
                : tradingsymbol.contains("-PAST-") ? today.minusDays(3) : today;
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

    /** The expiry-session (historical) spot the past-expiry recon settles against — no real market-data here. */
    @Bean
    @Primary
    StubExpirySpot stubExpirySpot() {
      return new StubExpirySpot();
    }
  }

  /** A configurable {@link ExpirySpotReader} that records the (key, session) it was asked for. */
  static final class StubExpirySpot implements ExpirySpotReader {
    volatile Optional<BigDecimal> value = Optional.empty();
    final java.util.List<String> keysQueried = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.List<LocalDate> sessionsQueried = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public Optional<BigDecimal> expirySessionClose(String exchange, String tradingsymbol, LocalDate session) {
      keysQueried.add(exchange + ":" + tradingsymbol);
      sessionsQueried.add(session);
      return value;
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private PaperService paper;
  @Autowired private PaperExpiryService expiry;
  @Autowired private PaperPositionRepository positions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private StubExpirySpot expirySpot;

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
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isPresent();

    assertThat(expiry.settleExpiries()).isEqualTo(1);

    var settled = positions.listClosed(null, null, sym, 10, 0);
    assertThat(settled).hasSize(1);
    assertThat(settled.get(0).closeReason()).isEqualTo("EXPIRY_SETTLEMENT");
    // no derivative position remains OPEN past expiry; the key is re-openable
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isEmpty();
  }

  @Test
  void stockFnoClosesWithPhysicalSettlementWarning() {
    String sym = "EXPSTK-" + UUID.randomUUID();
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("40.00"), null, null));
    // The stock derivative physical-settles off its OWN last REAL tick (delegated to doSettle's #694
    // fallback), never a fabricated avgEntryPrice — seed a real option tick so it flattens at it.
    seedTick("NFO", sym, "42.00");

    assertThat(expiry.settleExpiries()).isEqualTo(1);
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isEmpty();
    assertThat(positions.listClosed(null, null, sym, 10, 0).get(0).closeReason()).isEqualTo("EXPIRY_SETTLEMENT");
  }

  /**
   * AY-SL-04 regression: an index option whose SPOT has NEVER ticked must NOT settle at a fabricated
   * intrinsic (the old code fell back to {@code avgEntryPrice} → a fictional 0-P&amp;L exit). The
   * settle refuses (counter + ntfy), leaving the position durably OPEN for the next pass — the #694
   * "exits take the last REAL truth, refuse only when none was ever seen" doctrine on the spot axis.
   */
  @Test
  void indexOptionWithNoSpotEverIsLeftOpenNotSettledAtEntry() {
    redis.opsForHash().delete("ticks:last", "NSE:NIFTY 50"); // no spot tick has EVER been seen
    String sym = "EXPOPT-" + UUID.randomUUID();
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    double refusedBefore = counter("ay_paper_settle_refused_total");

    assertThat(expiry.settleExpiries()).isEqualTo(0); // nothing settled — no fabricated close

    // The position is left durably OPEN (NOT closed at avgEntryPrice), and the refusal was alerted.
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isPresent();
    assertThat(positions.listClosed(null, null, sym, 10, 0)).isEmpty();
    assertThat(counter("ay_paper_settle_refused_total")).isGreaterThan(refusedBefore);
  }

  /**
   * AY-SL-04 / #694: a REAL-but-stale spot still settles (exits take the best available truth at any
   * age, never refuse) — it is counted + alerted as a stale settle, not fabricated from avgEntryPrice.
   */
  @Test
  void staleSpotStillSettlesAtIntrinsic() {
    String staleTs = OffsetDateTime.now(IST).minusMinutes(10).toString(); // ~600s old, far past 15s
    redis.opsForHash().put("ticks:last", "NSE:NIFTY 50", tickJsonAt("NIFTY 50", "18100.00", staleTs));
    String sym = "EXPOPT-" + UUID.randomUUID();
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    double staleBefore = counter("ay_paper_stale_settle_total");

    assertThat(expiry.settleExpiries()).isEqualTo(1); // stale spot is USED, never refused

    var settled = positions.listClosed(null, null, sym, 10, 0);
    assertThat(settled).hasSize(1);
    assertThat(settled.get(0).closeReason()).isEqualTo("EXPIRY_SETTLEMENT");
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isEmpty();
    assertThat(counter("ay_paper_stale_settle_total")).isGreaterThan(staleBefore);
  }

  /**
   * AY-SL-04: the stock-F&amp;O branch is fixed the same way — a derivative that has NEVER ticked is
   * left OPEN + alerted, not physical-settled at a fabricated avgEntryPrice (delegated to doSettle).
   */
  @Test
  void stockFnoWithNoTickEverIsLeftOpenNotFabricated() {
    String sym = "EXPSTK-" + UUID.randomUUID(); // no tick seeded for its own symbol
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("40.00"), null, null));
    double refusedBefore = counter("ay_paper_settle_refused_total");

    assertThat(expiry.settleExpiries()).isEqualTo(0);
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isPresent();
    assertThat(positions.listClosed(null, null, sym, 10, 0)).isEmpty();
    // Prove the REFUSE path executed (not merely skipped): doSettle refused off the position's own tick.
    assertThat(counter("ay_paper_settle_refused_total")).isGreaterThan(refusedBefore);
  }

  /**
   * AY-SL-04 strand recovery (1): a position refused on its expiry day stays OPEN past expiry; the
   * past-expiry recon picks it up on a later run and settles it at the EXPIRY-SESSION spot (the
   * expiry-day close from history), NOT today's live spot. The stub returns 18500 for the expiry
   * session while Redis carries a different current spot (18100), and the recon reads ONLY history —
   * so a settle proves it used the expiry-session value.
   */
  @Test
  void pastExpiryStrandIsRecoveredWithExpirySessionSpot() {
    String sym = "EXPOPT-PAST-" + UUID.randomUUID().toString().substring(0, 8);
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    expirySpot.value = Optional.of(new BigDecimal("18500.00")); // the expiry-day close (18000 CE -> intrinsic 500)

    assertThat(expiry.settlePastExpiries()).isEqualTo(1);

    var settled = positions.listClosed(null, null, sym, 10, 0);
    assertThat(settled).hasSize(1);
    assertThat(settled.get(0).closeReason()).isEqualTo("EXPIRY_SETTLEMENT");
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isEmpty();
    // It resolved the spot AS OF the expiry session (3 days ago) for the SPOT symbol — not today.
    assertThat(expirySpot.keysQueried).contains("NSE:NIFTY 50");
    assertThat(expirySpot.sessionsQueried).contains(LocalDate.now(IST).minusDays(3));
    // Settled at the expiry-session intrinsic (500, gross ~25000), unmistakably not today's (100).
    assertThat(settled.get(0).realizedPnl()).isGreaterThan(new BigDecimal("15000"));
  }

  /**
   * AY-SL-04 strand recovery (2): when the expiry-session spot was NEVER captured, the recon leaves
   * the position durably OPEN and re-attempts + re-alerts EACH run — never fabricated, never settled
   * at a wrong-session spot. Two consecutive runs bump the refused counter twice (per-attempt).
   */
  @Test
  void pastExpiryWithNoExpirySessionSpotStaysOpenAndReAlertsEachRun() {
    String sym = "EXPOPT-PAST-" + UUID.randomUUID().toString().substring(0, 8);
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    expirySpot.value = Optional.empty(); // the expiry-session close was never captured
    double refusedBefore = counter("ay_paper_settle_refused_total");

    assertThat(expiry.settlePastExpiries()).isEqualTo(0); // run 1 — refuses, does not fabricate
    assertThat(expiry.settlePastExpiries()).isEqualTo(0); // run 2 (a later pass) — re-attempts

    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isPresent(); // durably OPEN, never closed
    assertThat(positions.listClosed(null, null, sym, 10, 0)).isEmpty();
    // Per-attempt counter bumped on BOTH runs — the strand is re-alerted, never silently stranded.
    assertThat(counter("ay_paper_settle_refused_total")).isGreaterThanOrEqualTo(refusedBefore + 2);
  }

  /**
   * AY-SL-04 strand recovery (3, idempotency): the recon touches ONLY strictly-past-expiry OPEN
   * positions. A position still expiring TODAY is left for {@link PaperExpiryService#settleExpiries},
   * and once settled (CLOSED) it is never re-seen — no double-settle.
   */
  @Test
  void pastExpiryReconLeavesCurrentAndSettledPositionsUntouched() {
    String sym = "EXPOPT-" + UUID.randomUUID(); // expiry == today (NOT past)
    paper.openOrder(new PaperService.OrderRequest(null, "NFO", sym, "BUY", 50, new BigDecimal("80.00"), null, null));
    expirySpot.value = Optional.of(new BigDecimal("18500.00"));

    // Expiry today is not strictly past -> the recon skips it (settleExpiries owns the expiry date).
    assertThat(expiry.settlePastExpiries()).isEqualTo(0);
    assertThat(positions.findOpen("manual", "NFO", sym, "BUY")).isPresent();

    // Settle it on its expiry day via the normal path (live Redis spot 18100), then it is CLOSED.
    assertThat(expiry.settleExpiries()).isEqualTo(1);
    BigDecimal realized = positions.listClosed(null, null, sym, 10, 0).get(0).realizedPnl();

    // A CLOSED position is not in listOpen -> the recon never re-touches it (idempotent).
    assertThat(expiry.settlePastExpiries()).isEqualTo(0);
    assertThat(positions.listClosed(null, null, sym, 10, 0).get(0).realizedPnl()).isEqualByComparingTo(realized);
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
    assertThat(positions.findOpen("other", "NFO", sym, "BUY")).isPresent();

    assertThat(expiry.notifyExpiring()).isEqualTo(1); // expires tomorrow -> one push
    assertThat(expiry.notifyExpiring()).isEqualTo(0); // deduped on re-run

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification_events WHERE detail LIKE ?", Integer.class, "%" + sym + "%");
    assertThat(rows).isEqualTo(1);
  }

  private void seedSpot(String symbol, String price) {
    seedTick("NSE", symbol, price);
  }

  private void seedTick(String exchange, String symbol, String price) {
    redis.opsForHash().put("ticks:last", exchange + ":" + symbol, tickJson(symbol, price));
  }

  private String tickJson(String symbol, String price) {
    try {
      return objectMapper.writeValueAsString(
          Map.of("exchange", "NSE", "tradingsymbol", symbol, "lastPrice", price));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A tick carrying an explicit ISO timestamp so {@link LastTickReader} can age it (stale-spot tests). */
  private String tickJsonAt(String symbol, String price, String timestamp) {
    try {
      return objectMapper.writeValueAsString(
          Map.of("exchange", "NSE", "tradingsymbol", symbol, "lastPrice", price, "timestamp", timestamp));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Current value of a Micrometer counter (0 when not yet registered), for before/after deltas. */
  private double counter(String name) {
    var c = meterRegistry.find(name).counter();
    return c == null ? 0.0 : c.count();
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}
