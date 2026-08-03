package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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
 * TRIPWIRE for a KNOWN, UNFIXED corporate-action hazard on the intraday paper surface.
 *
 * <p><b>The hazard.</b> {@link PaperBracketEvaluator} prices every open position off the {@code
 * ticks:last} LTP and compares it to the STORED {@code paper_positions.stop_loss} - a level the daily
 * swing batch writes once, at entry, on the corporate-action plane current then, and which nothing
 * ever re-scales (its only other writer is a human bracket PATCH). When a split or bonus
 * retroactively re-planes the market the tick halves while the stored stop stays whole, and this
 * poller would stop the holding out INTRADAY, on the ex-date morning, at a price that never happened
 * - before the daily batch, which carries its own version of the same hazard, ever runs.
 *
 * <p><b>Why it is unfixed, deliberately.</b> PR #1251 attempted a fix on both surfaces: re-anchoring
 * the batch path's entry reference onto the series plane, and skipping EOD-managed books here.
 * Cross-vendor review found four Criticals and the owner reverted both. Recorded so the next reader
 * does not re-derive them:
 *
 * <ol>
 *   <li>The batch-path re-anchor keyed on a 0.5% tolerance that never verifies a corporate action.
 *       Authoritative history deliberately overwrites poisoned or corrected candles, so a stored 100
 *       / corrected 101 / current 92.50 would move an 8% stop from 92.00 to 92.92 and MANUFACTURE an
 *       exit on non-CA data - the exact inverse of the intent. The "smallest plausible ratio is a
 *       1:20 bonus" premise is unenforced anyway: the CA subject parser accepts arbitrary ratios.
 *   <li>Skipping by book strands supported positions. {@code pos.book()} is operator-selectable and
 *       the UI explicitly permits routing an NFO option into a swing book; those legs would lose
 *       SL/TP while the swing batch cannot own a non-swing derivative.
 *   <li>Disarming a family, or its last enabled/published strategy, makes the batch return before its
 *       exit pass, and catch-up records the disarmed session without replay. With the intraday
 *       evaluator also skipping the book there would be NO automatic exit at all.
 *   <li>The adjusted plane is local to one decision. Stored entry, paper average entry and quantity
 *       all stay on the old plane, so the sell-decision report can say SELL while the engine holds,
 *       and a 1:2 split of 10 x 150 closing at 68 still books as 10 x (68 - 150).
 * </ol>
 *
 * <p><b>What actually holds the line today, and why that is thin.</b> Only that no cash equity is on
 * the live tick feed, so {@code lastTick} returns empty, {@code ltp} is null and {@code breach} never
 * runs - an accident of subscription, not a design (measured: {@code ticks:last} holds 181 fields,
 * zero equities, and all 9 automated swing closes to date came from the 20:05 batch). The other half
 * of the arming condition is ALREADY true: all 17 open cash-equity swing holdings carry a non-null
 * {@code stop_loss}. One tick is the entire remaining distance. That distance is what {@code
 * SubscriptionRegistry.refuseCashEquity} now ratchets, in market-data, at the one boundary every
 * subscription path shares.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingEquityBracketTripwireIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String LAST_TICK_HASH = "ticks:last";

  /**
   * The failure text is a deliverable: a reader hitting this in CI later has none of the context, so
   * it carries the whole chain - what changed, why it matters, and what NOT to do about it.
   */
  private static final String FIXED_UPSTREAM =
      "This test CHARACTERISES a known, unfixed hazard, and it just stopped reproducing.%n"
          + "PaperBracketEvaluator compared a live tick against the STORED"
          + " paper_positions.stop_loss - a level written once at entry, on the corporate-action"
          + " plane current then, and never re-scaled. This test feeds it a tick that is a clean 1:2"
          + " split of the entry: the holding has not lost a rupee, and the stop fires anyway. That"
          + " it no longer fires means somebody changed this surface.%n"
          + "If that was deliberate, good - but PR #1251 tried twice and cross-vendor review found"
          + " four Criticals (see this class's javadoc; in particular a tolerance-based re-anchor"
          + " manufactures exits on CORRECTED non-CA candles, and a book-keyed skip strands"
          + " operator-routed derivative legs). Check the fix against all four before deleting this"
          + " class, and update the doctrine note in"
          + " docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md.%n"
          + "If it was NOT deliberate, something moved under the paper exit path unnoticed.";

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
  @Autowired private StringRedisTemplate redis;
  @Autowired private JdbcTemplate jdbc;

  /** The property {@link PaperStaleTickAlerter} reads - pinned to the compile-time authority below. */
  @Value("${artha.paper.eod-managed-books:minervini,manas-arora}")
  private String eodManagedBooksProperty;

  @Test
  void aSplitShapedTickStillStopsOutASwingHoldingOffTheStoredPreSplitLevel() {
    // Production-shaped row: entry 200 with the batch's 8%-of-entry stop at 184 - the shape of every
    // live swing holding (non-null stop_loss, null take_profit). The tick is 100, a 1:2 split of the
    // 200 entry: the position is worth exactly what it was, expressed on the new plane. The poller
    // sees 100 <= 184 and closes it. Nothing in the chain knows a corporate action happened.
    String symbol = unique("TWA");
    long id = openHolding(symbol, Books.MINERVINI, "200.0000", "184.0000");

    evaluateWithTick(symbol, "100.0000");

    assertThat(status(id)).as(FIXED_UPSTREAM).isEqualTo("CLOSED");
    assertThat(closeReason(id)).as(FIXED_UPSTREAM).isEqualTo("STOP_LOSS");
  }

  @Test
  void theHazardIsNotSwingSpecificItIsAnyBookHoldingAStaleStoredLevel() {
    // Round 2 of #1251 tried to close this by skipping swing BOOKS. Critical 2 killed that: pos.book()
    // is operator-selectable and the UI permits routing a derivative leg into a swing book, so a
    // book-keyed skip strands supported positions while leaving the same stale-level comparison in
    // place for everything else. Pinning the scalper book here states that plainly - the defect is the
    // stored level, not the book label, so any future fix must be about the level.
    String symbol = unique("TWB");
    long id = openHolding(symbol, Books.SCALPER, "200.0000", "184.0000");

    evaluateWithTick(symbol, "100.0000");

    assertThat(status(id)).isEqualTo("CLOSED");
    assertThat(closeReason(id)).isEqualTo("STOP_LOSS");
  }

  @Test
  void theTwoEodManagedBookAuthoritiesAgree() {
    // PaperStaleTickAlerter's own comment: "Keep this set in step with PaperService.isSwingBook - a
    // new swing family must land in BOTH." Nothing enforced it. PaperService.isSwingBook now reads
    // Books.eodManaged() (byte-identical - BookResolver.MINERVINI/MANAS_ARORA ARE those constants),
    // so there is one behavioural authority; this pins the alerting property's default to it.
    Set<String> fromProperty =
        Arrays.stream(eodManagedBooksProperty.trim().split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());

    assertThat(fromProperty)
        .as(
            "artha.paper.eod-managed-books (alert suppression) and Books.eodManaged() (the"
                + " behavioural authority behind PaperService.isSwingBook) must name the same books"
                + " - a new swing family has to land in both, or one surface treats it as"
                + " EOD-managed and the other does not")
        .isEqualTo(Books.eodManaged());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** Seeds a live tick, runs the real evaluator, and always removes the tick again (shared Redis). */
  private void evaluateWithTick(String symbol, String lastPrice) {
    redis
        .opsForHash()
        .put(
            LAST_TICK_HASH,
            EX + ":" + symbol,
            "{\"lastPrice\":\"" + lastPrice + "\",\"timestamp\":\"" + OffsetDateTime.now(IST) + "\"}");
    try {
      bracket.evaluate();
    } finally {
      redis.opsForHash().delete(LAST_TICK_HASH, EX + ":" + symbol);
    }
  }

  /** A production-shaped open holding, written straight to the ledger (no governor path). */
  private long openHolding(String symbol, String book, String entry, String stop) {
    return jdbc.queryForObject(
        "INSERT INTO paper_positions"
            + " (exchange, tradingsymbol, side, qty, avg_entry_price, status, stop_loss, book)"
            + " VALUES (?, ?, 'BUY', 10, ?::numeric, 'OPEN', ?::numeric, ?) RETURNING id",
        Long.class,
        EX,
        symbol,
        entry,
        stop,
        book);
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
