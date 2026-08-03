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
 * TRIPWIRE for the corporate-action plane hazard on the INTRADAY paper surface.
 *
 * <p><b>Re-scoped once already — read this before changing it.</b> When first written (PR #1251,
 * round 1) this class guarded an exposure that was live but unreachable: {@link
 * PaperBracketEvaluator} prices every open position off the {@code ticks:last} LTP and compares it to
 * the STORED {@code paper_positions.stop_loss} — a level the daily swing batch wrote once at entry, on
 * the corporate-action plane current then, which nothing re-scales. A split would have halved the tick
 * while the stored stop stayed whole and stopped the holding out intraday, at a price that never
 * happened. It could not fire only because no cash equity is on the live tick feed — an accident of
 * subscription, not a design — while the other half of the arming condition was already true (every
 * open swing holding carries a non-null {@code stop_loss}). Round 1 therefore guarded "a swing holding
 * has a tick"; the owner then escalated from guard to FIX on exactly that one-condition-wide
 * measurement, and {@link PaperBracketEvaluator} now skips {@link Books#eodManaged()} outright.
 *
 * <p>That fix made round 1's two assertions wrong in both directions — "no swing holding has a tick"
 * would now redden on a change that is no longer dangerous, and "the stored stop still stops it out"
 * is precisely the behaviour that was removed. A guard that fires on a safe change, or one that
 * silently always passes, is worse than none, so both were replaced rather than left. What is guarded
 * now:
 *
 * <ul>
 *   <li>{@link #aSwingHoldingIsNotClosedByTheIntradayBracketEvenWhenItTicks} — the fix itself. Reddens
 *       if the skip is removed or stops covering a swing book, which is the moment the exposure
 *       returns. The split-shaped tick is the literal condition it guards.
 *   <li>{@link #aScalperPositionIsStillClosedByTheIntradayBracket} — the opposite failure. The skip
 *       must be NARROW: the options books' 15-second stop is a real exit, and a skip that quietly
 *       widened to every book would disarm them with every swing test still green.
 *   <li>{@link #theTwoEodManagedBookAuthoritiesAgree} — the drift {@link PaperStaleTickAlerter}'s own
 *       comment warns about, previously unenforced and now load-bearing, since the skip keys off a
 *       book set.
 * </ul>
 *
 * <p>The other half of the fix — that the daily batch still exits these holdings, so the skip strands
 * nothing — is pinned in {@code SwingPaperExitCriticalsIntegrationTest
 * .theIntradayBracketSkipsASwingHoldingAndTheDailyBatchStillExitsIt}, which needs the real engine.
 * Neither test here has any environmental dependency; round 1's first test did (it read whatever
 * {@code ticks:last} the suite was pointed at, which under CI is an empty container), and that
 * weakness is part of why the owner asked for the fix plus a subscription-side ratchet instead. The
 * ratchet lives in {@code PinnedIndicesSubscriber}.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingEquityBracketTripwireIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final String EX = "NSE";
  private static final String LAST_TICK_HASH = "ticks:last";

  /**
   * The failure text is a deliverable: a reader hitting this in CI years from now has none of the
   * context, so it carries the whole chain — what broke, why it matters, what to do.
   */
  private static final String SKIP_GONE =
      "TRIPWIRE (PR #1251): an EOD-managed swing holding was CLOSED by the intraday bracket poller.%n"
          + "PaperBracketEvaluator prices open positions off the ticks:last LTP and compares that to"
          + " the STORED paper_positions.stop_loss - a level the daily swing batch wrote ONCE, at"
          + " entry, on the corporate-action plane current then, and which nothing re-scales when a"
          + " split or bonus retroactively re-planes the market. This test feeds it a tick that is a"
          + " clean 1:2 split of the entry: the holding has not lost a rupee, yet breach() sees"
          + " LTP <= stop.%n"
          + "#1251 closed this by skipping Books.eodManaged() in PaperBracketEvaluator, making the"
          + " already-merged doctrine ('the swing books stay EOD-managed' -"
          + " docs/signal-analysis/2026-08-02-manas-exit-stop-doctrine.md) true in code rather than"
          + " merely true by accident of what is subscribed. If this reddens, that skip is gone or no"
          + " longer covers this book, and a corporate action can once again stop a swing holding out"
          + " intraday at a price that never happened - on the ex-date morning, before the daily batch"
          + " runs.%n"
          + "Restore the skip. Making the swing books intraday-managed is a doctrine change, not a"
          + " refactor: the stored level would have to be re-planed first.";

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

  /** The property {@link PaperStaleTickAlerter} reads — pinned to the compile-time authority below. */
  @Value("${artha.paper.eod-managed-books:minervini,manas-arora}")
  private String eodManagedBooksProperty;

  @Test
  void aSwingHoldingIsNotClosedByTheIntradayBracketEvenWhenItTicks() {
    // Production-shaped row: entry 200 with the batch's 8%-of-entry stop at 184 — the shape of every
    // live swing holding (non-null stop_loss, null take_profit). The tick is 100, a 1:2 split of the
    // 200 entry: the position is worth exactly what it was, expressed on the new plane.
    String symbol = unique("TWA");
    long id = openHolding(symbol, Books.MINERVINI, "200.0000", "184.0000");

    evaluateWithTick(symbol, "100.0000");

    assertThat(status(id)).as(SKIP_GONE).isEqualTo("OPEN");
  }

  @Test
  void aScalperPositionIsStillClosedByTheIntradayBracket() {
    // Same numbers, so the book is the ONLY difference under test. The scalper book's legs really do
    // tick and their 15-second stop is a real exit path — if the skip ever widened to cover them it
    // would disarm that stop silently, and no swing-side assertion could see it.
    String symbol = unique("TWB");
    long id = openHolding(symbol, Books.SCALPER, "200.0000", "184.0000");

    evaluateWithTick(symbol, "100.0000");

    assertThat(status(id))
        .as(
            "the #1251 skip must cover EOD-managed books ONLY — if a scalper position stops being"
                + " evaluated, the intraday stop has been disarmed for the books that depend on it")
        .isEqualTo("CLOSED");
    assertThat(closeReason(id)).isEqualTo("STOP_LOSS");
  }

  @Test
  void theTwoEodManagedBookAuthoritiesAgree() {
    // PaperStaleTickAlerter's own comment: "Keep this set in step with PaperService.isSwingBook — a
    // new swing family must land in BOTH." Nothing enforced that. Now that PaperBracketEvaluator's
    // skip keys off Books.eodManaged(), a drift would leave one surface treating a book as
    // EOD-managed while another does not, so the property's shipped default is pinned to it.
    Set<String> fromProperty =
        Arrays.stream(eodManagedBooksProperty.trim().split("\\s*,\\s*"))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toUnmodifiableSet());

    assertThat(fromProperty)
        .as(
            "artha.paper.eod-managed-books (alert suppression) and Books.eodManaged() (the"
                + " behavioural authority behind PaperBracketEvaluator's skip and"
                + " PaperService.isSwingBook) must name the same books — a new swing family has to"
                + " land in both, or one surface treats it as EOD-managed and the other does not")
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
