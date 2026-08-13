package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The mark-to-market price for a CASH EQUITY position, held IN MEMORY ONLY — never a database write,
 * never a new column. Deliberately mirrors {@link ManasGoverningStopCache}: same package, same
 * lifecycle, same "populated by the swing batch through the {@code EmissionGuard} port" shape.
 *
 * <p><b>Why this exists.</b> {@link PaperAccountService#unrealizedTotal} marks every open position
 * through {@link LastTickReader} (the Redis {@code ticks:last} hash) and falls back to
 * {@code avgEntryPrice} on a miss — which scores the position at exactly zero unrealized. That hash
 * is written from the live WS ticker, whose subscription is the futures/options universe: measured
 * 2026-08-13, {@code HLEN ticks:last} = 307 and NOT ONE entry is an NSE cash-equity symbol, so
 * EVERY swing position (manas-arora + minervini, 18 open) marked at its own entry price and the
 * books' whole +₹27,213.97 of unrealized gain was invisible to book equity. The swing exit pass
 * already settles these same positions at the daily-bar close for precisely this reason
 * ({@code SwingBatchEngine}: "the equities don't tick, so an LTP close would book breakeven"), so
 * the correct mark was already in hand once a day and simply never captured.
 *
 * <p><b>Why not fetch one.</b> The three obvious sources — {@code MarketDataCandlesClient}, a
 * {@code marketdata.candles} read, a market-data quote endpoint — are all blocking HTTP, and
 * {@code unrealizedTotal} is the wrong place for any of them. It runs FOUR times inside
 * {@code PaperService#openOrder}'s {@code @Transactional} fill (the repo's own rule, stated at
 * {@code SignalEngine}'s sizing call site, is that a DB transaction is never held open across an
 * HTTP call), three times per {@code GET /api/v1/paper/account}, and once per candidate inside the
 * swing batch — all on the single shared {@code ThreadPoolTaskScheduler} that also drives the
 * 15-second SL/TP bracket sweep. A per-position 10s-timeout fetch there would stall EXITS, which
 * this change is required not to touch. Capturing the close the exit pass is already holding costs
 * one map write and zero round-trips.
 *
 * <p><b>Freshness.</b> Entries carry the session they belong to and the instant they were captured;
 * {@link #price} judges age on the SESSION (default 5 calendar days,
 * {@code artha.paper.equity-mark.max-session-age-days}) and treats anything older as ABSENT rather
 * than serving a stale price into a money figure. Capture time is kept for diagnostics only — see
 * {@link #price} for why it is the wrong clock to gate on.
 *
 * <p><b>Hydration and the entry pass.</b> {@code SwingBatchEngine} warms every held symbol's mark at
 * the START of a run, BEFORE the entry pass, then the exit pass refreshes it from the bar it already
 * holds. The warm exists because entries run before exits: without it the whole entry pass would read
 * an equity that values every held position at cost, hiding existing losses from the sizing and
 * admission rails on every restart. The warm deliberately does not share the exit pass's series cache
 * — re-sampling a still-forming daily bar at a different instant could change an exit, and exits must
 * stay byte-identical.
 *
 * <p><b>A miss is visible AND blocking, on the books that are warmed.</b> Callers still fall back to
 * {@code avgEntryPrice} for DISPLAY and for the equity arithmetic itself (no NULL propagation into
 * the account API, no refused batch, no changed exit), and the condition is surfaced —
 * {@code AccountDto.unmarkedPositions}, the {@code ay_paper_mtm_blind_positions} gauge, a WARN. But
 * because that fallback erases a position's unrealized LOSS and therefore INFLATES equity,
 * {@link PaperEmissionGuard#entryVeto} refuses AUTOMATED entry on a partially-marked book
 * ({@code RiskService#EQUITY_UNMARKED}). Scoped to the warmed books only: a book nobody warms would
 * otherwise refuse entries forever. Exits and manual orders are never blocked.
 */
@Component
public class EquityMarkCache {

  /** A captured daily-bar close: the price, the session it closed, and when it was captured. */
  public record Mark(BigDecimal price, LocalDate session, Instant capturedAt) {}

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final ConcurrentHashMap<String, Mark> marks = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int maxSessionAgeDays;

  /** Wires the (test-overridable) clock and the staleness bound. */
  public EquityMarkCache(
      Clock clock,
      @Value("${artha.paper.equity-mark.max-session-age-days:5}") int maxSessionAgeDays) {
    this.clock = clock;
    this.maxSessionAgeDays = maxSessionAgeDays;
  }

  /** The stable {@code EXCHANGE:TRADINGSYMBOL} key — the same grammar {@link LastTickReader} uses. */
  private static String key(String exchange, String tradingsymbol) {
    return exchange + ":" + tradingsymbol;
  }

  /**
   * Records a session's closing price for a symbol, keeping whichever entry belongs to the LATER
   * SESSION (ties go to the newer write, so a same-session correction still lands).
   *
   * <p><b>Session-monotonic, but NOT a price ratchet</b> — two different axes, and only one of them
   * may be pinned. Unlike {@link ManasGoverningStopCache#put} this must let the PRICE fall: a mark is
   * a market fact, and a "never loosens" rule would pin book equity to each position's high-water
   * close and systematically overstate it. What must not go backwards is the SESSION.
   *
   * <p>Why that matters here (cross-vendor review, 2026-08-13): the key is {@code EXCHANGE:SYMBOL},
   * deliberately shared across books because a close is a property of the symbol — and three symbols
   * (AVALON, PRECOT, KANORICHEM) are held by BOTH swing books today. Two doctrines write this cache in
   * the same batch cycle, and a CATCH-UP run pins a past session ({@code requiredBarDate}) and
   * legitimately evaluates that session's bar. Unconditional last-write-wins therefore let one book's
   * historical replay clobber the other book's current mark — with a fresh capture instant on it. The
   * read-side session bound alone does not close that: the old bar would simply be refused, silently
   * dropping a mark that WAS available. Rejecting the stale write keeps the good one.
   *
   * <p>A null or non-positive price is ignored (a zero close is not a real mark); so is a null
   * session, since freshness could not then be judged.
   */
  public void put(String exchange, String tradingsymbol, BigDecimal price, LocalDate session) {
    if (price == null || price.signum() <= 0 || session == null) {
      return;
    }
    marks.merge(
        key(exchange, tradingsymbol),
        new Mark(price, session, clock.instant()),
        (existing, incoming) ->
            incoming.session().isBefore(existing.session()) ? existing : incoming);
  }

  /**
   * The cached mark if the SESSION it closed is recent enough, else empty (never a stale price).
   *
   * <p><b>Freshness is judged on the bar's own session, NOT on when we captured it</b> (cross-vendor
   * review, 2026-08-13). Capture time is the wrong clock: it measures when this process last ran the
   * exit pass, not how old the PRICE is, and three real paths refresh the timestamp on an old bar —
   * a catch-up run pins a PAST session via {@code requiredBarDate} and legitimately evaluates that
   * session's bar; {@code MarketDataCandlesClient} fail-softs a STALE endpoint response and logs
   * "data used unchanged"; and a symbol that stops printing keeps re-serving its last bar. Under a
   * capture-time bound each of those would look permanently fresh while the price aged without
   * limit. Session age cannot be fooled that way, and it still catches a dead batch for free: no new
   * captures means the stored session stops advancing.
   *
   * <p>CALENDAR days, deliberately, not trading sessions. {@code libs/market-calendar} could count
   * sessions exactly, but it THROWS outside its bundled year range, and this method is reached four
   * times inside {@code PaperService#openOrder}'s {@code @Transactional} fill — turning a bounded
   * read into an exception on the money path to buy a day of precision is the wrong trade. The
   * default (5) clears a Friday close read on the following Wednesday, i.e. a long weekend plus a
   * holiday.
   */
  public Optional<BigDecimal> price(String exchange, String tradingsymbol) {
    Mark mark = marks.get(key(exchange, tradingsymbol));
    if (mark == null || mark.session() == null) {
      return Optional.empty();
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    long age = ChronoUnit.DAYS.between(mark.session(), today);
    // A future-dated session (clock skew, a mis-stamped bar) is refused too: it is not evidence of
    // freshness, it is evidence something is wrong, and `age` would go negative and pass silently.
    return age < 0 || age > maxSessionAgeDays ? Optional.empty() : Optional.of(mark.price());
  }

  /** The raw entry (price + session + capture instant), ignoring staleness — for diagnostics. */
  public Optional<Mark> mark(String exchange, String tradingsymbol) {
    return Optional.ofNullable(marks.get(key(exchange, tradingsymbol)));
  }

  /** Drops a symbol's mark. Memory hygiene only — a miss is always safe. */
  public void evict(String exchange, String tradingsymbol) {
    marks.remove(key(exchange, tradingsymbol));
  }

  /** How many marks are currently held (diagnostics / tests). */
  public int size() {
    return marks.size();
  }
}
