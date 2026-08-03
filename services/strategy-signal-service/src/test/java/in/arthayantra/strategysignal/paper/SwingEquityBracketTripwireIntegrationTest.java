package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.paper.PaperPositionRepository.PositionRow;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TRIPWIRE for the corporate-action plane hazard on the INTRADAY paper surface — the half of the
 * defect that PR #1251 deliberately did NOT fix.
 *
 * <p>#1251 fixed the BATCH path: {@code SwingBatchEngine.entryReference} re-anchors a held swing
 * position's entry reference onto the plane of the daily series it is compared against, so a
 * retroactive split rewrite can no longer manufacture a stop-out there. It left {@link
 * PaperBracketEvaluator} alone, and that is the residual: the 15-second poller reads a position's
 * live LTP straight out of {@code ticks:last} and compares it against the STORED {@code
 * paper_positions.stop_loss} scalar — a level the swing batch computed off the stored entry price on
 * the PRE-corporate-action plane, which nothing ever rewrites. A 1:2 split on a held swing name would
 * halve the tick while the stored stop stayed whole, and the position would be stopped out INTRADAY,
 * on the ex-date morning, before the daily batch #1251 fixed ever ran.
 *
 * <p><b>Why it was left alone, and why that is not safety.</b> The path is unreachable today only
 * because cash equities are not on the live tick subscription — measured 2026-08-03: {@code
 * ticks:last} held 181 fields (88 BFO, 86 NFO, 5 NSE indices, 2 BSE indices) and ZERO equities, so
 * {@code lastTick} returns empty for every swing holding, {@code ltp} is null and {@link
 * PaperBracketEvaluator#breach} is never reached. That is an accident of what is subscribed, not a
 * design: the OTHER half of the arming condition is already satisfied — all 17 open cash-equity swing
 * positions carry a non-null {@code stop_loss} today. One tick is the entire remaining distance.
 * {@link PaperStaleTickAlerter}'s {@code eod-managed-books} exemption records the same fact from the
 * other side ("their cash-equity holdings are not on the live tick subscription").
 *
 * <p><b>The two tests are the two halves of one sentence</b> — "fail if an equity ever reaches that
 * comparison WHILE the evaluator still compares a live tick against the stored scalar":
 * <ul>
 *   <li>{@link #noSwingEquityHoldingIsReachableByTheIntradayBracketComparison} guards the first
 *       clause. It reddens the day a swing holding gets a tick — i.e. the day the exposure arms.
 *   <li>{@link #theStoredStopIsStillComparedToARawTickSoASplitStopsTheHoldingOut} guards the second.
 *       It reddens the day the intraday surface IS fixed — at which point this whole class should be
 *       deleted, not repaired.
 * </ul>
 *
 * <p><b>Known limit, stated so nobody over-reads it.</b> The first test observes the {@code
 * ticks:last} hash of whatever Redis the suite is pointed at. Under CI that is an empty
 * Testcontainers instance with no ticker feeding it, so the assertion cannot fail there for
 * environmental reasons — its reach is (a) any run against a Redis a live ticker feeds, and (b) being
 * the artifact a future reader collides with when they change the subscription universe and go
 * looking for what depended on it. The second test has no environmental dependency at all.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingEquityBracketTripwireIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String LAST_TICK_HASH = "ticks:last";

  /**
   * The failure text is the deliverable here — a reader hitting this in CI years from now has none of
   * the context, so the message carries the whole chain: what armed, why it was inert, what breaks.
   */
  private static final String ARMED =
      "TRIPWIRE ARMED (PR #1251): paper book '%s' holds cash-equity position #%d (%s:%s) and a LIVE"
          + " TICK now exists for it.%n"
          + "PaperBracketEvaluator prices every open position straight off ticks:last and compares"
          + " that LTP to the STORED paper_positions.stop_loss scalar. For a swing holding that"
          + " scalar was computed by the daily batch off the stored entry price, on the plane that"
          + " was current when the position opened — and NOTHING rewrites it when a corporate action"
          + " retroactively re-planes the market.%n"
          + "#1251 fixed exactly this mismatch on the BATCH path (SwingBatchEngine.entryReference)"
          + " and deliberately left this intraday surface alone, BECAUSE equities were not on the"
          + " tick subscription and breach() could therefore never run on one. That precondition has"
          + " just changed.%n"
          + "Consequence now live: a split on a held swing name halves the tick while the stored stop"
          + " stays whole, and the 15s poller stops the position out intraday at a price that never"
          + " happened — on the ex-date morning, before the fixed daily batch runs.%n"
          + "Fix the intraday surface (re-anchor the stored level, or suppress the bracket for"
          + " EOD-managed books) before this ships. Do not just delete this test.";

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    InstrumentMetaClient stubMeta() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.EQUITY, new BigDecimal("0.05"), 1);
    }
  }

  @Autowired private PaperBracketEvaluator bracket;
  @Autowired private PaperPositionRepository positions;
  @Autowired private LastTickReader lastTick;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;

  /**
   * Read from the SAME property {@link PaperStaleTickAlerter} reads, so the guarded population
   * follows production rather than a copy of it: these are the books whose holdings are cash equities
   * managed by the EOD batch rather than by the intraday poller.
   */
  @Value("${artha.paper.eod-managed-books:minervini,manas-arora}")
  private String eodManagedBooks;

  @Test
  void noSwingEquityHoldingIsReachableByTheIntradayBracketComparison() {
    // Seed one production-shaped row so the guarded population is never empty even on a clean
    // container: entry 200 with the batch's 8%-of-entry stop at 184, exactly the shape of the 17 live
    // rows (non-null stop_loss, null take_profit).
    String symbol = unique("TWA");
    openSwingEquityHolding(symbol, "200.0000", "184.0000");

    Set<String> books = eodManagedBooks();
    List<PositionRow> swingHoldings =
        positions.listOpen().stream()
            .filter(p -> books.contains(p.book()))
            .filter(p -> p.stopLoss() != null || p.takeProfit() != null)
            .toList();
    assertThat(swingHoldings)
        .as("the guarded population must not be empty, or this test proves nothing")
        .isNotEmpty();

    for (PositionRow pos : swingHoldings) {
      assertThat(lastTick.lastTick(pos.exchange(), pos.tradingsymbol()))
          .as(ARMED, pos.book(), pos.id(), pos.exchange(), pos.tradingsymbol())
          .isEmpty();
    }
  }

  @Test
  void theStoredStopIsStillComparedToARawTickSoASplitStopsTheHoldingOut() {
    // The second clause: this pins that the exposure is REAL and gated by nothing but the tick, and
    // it reddens the day somebody fixes the intraday surface — the signal to delete this class.
    // Scenario is the same 1:2 split #1251's batch-path test uses: the holding opened at 200 with the
    // batch's stop at 184; the split halves the market to 100 without costing the position a rupee,
    // and 100 <= 184 fires a stop that never happened.
    String symbol = unique("TWB");
    long id = openSwingEquityHolding(symbol, "200.0000", "184.0000");

    seedTick(symbol, "100.0000");
    try {
      bracket.evaluate();
    } finally {
      redis.opsForHash().delete(LAST_TICK_HASH, EX + ":" + symbol);
    }

    assertThat(status(id))
        .as(
            "PaperBracketEvaluator still compares the raw tick to the stored pre-split stop — if this"
                + " is no longer CLOSED the intraday surface has been fixed and this whole tripwire"
                + " class is obsolete: delete it rather than adjust it")
        .isEqualTo("CLOSED");
    assertThat(closeReason(id)).isEqualTo("STOP_LOSS");
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private Set<String> eodManagedBooks() {
    return Arrays.stream(eodManagedBooks.trim().split("\\s*,\\s*"))
        .filter(s -> !s.isBlank())
        .collect(Collectors.toUnmodifiableSet());
  }

  /** A production-shaped open swing holding, written straight to the ledger (no governor path). */
  private long openSwingEquityHolding(String symbol, String entry, String stop) {
    return jdbc.queryForObject(
        "INSERT INTO paper_positions"
            + " (exchange, tradingsymbol, side, qty, avg_entry_price, status, stop_loss, book)"
            + " VALUES (?, ?, 'BUY', 10, ?::numeric, 'OPEN', ?::numeric, 'minervini') RETURNING id",
        Long.class,
        EX,
        symbol,
        entry,
        stop);
  }

  private void seedTick(String symbol, String lastPrice) {
    String iso = OffsetDateTime.now(IST).toString();
    redis
        .opsForHash()
        .put(
            LAST_TICK_HASH,
            EX + ":" + symbol,
            "{\"lastPrice\":\"" + lastPrice + "\",\"timestamp\":\"" + iso + "\"}");
  }

  private String status(long id) {
    return jdbc.queryForObject("SELECT status FROM paper_positions WHERE id = ?", String.class, id);
  }

  private String closeReason(long id) {
    return jdbc.queryForObject(
        "SELECT close_reason FROM paper_positions WHERE id = ?", String.class, id);
  }

  /** ITs share the singleton DB with no cleanup, so every row needs its own symbol. */
  private static String unique(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }
}
