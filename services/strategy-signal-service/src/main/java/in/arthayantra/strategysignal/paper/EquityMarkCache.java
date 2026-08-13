package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * <p><b>Freshness.</b> Entries carry the session they belong to and the wall-clock instant they were
 * captured; {@link #price} treats anything older than {@code artha.paper.equity-mark.max-age-hours}
 * (default 96h — a long weekend plus a holiday) as ABSENT rather than serving a stale price into a
 * money figure. A mark is therefore at most one batch old: the exit pass that writes it runs after
 * the entry pass in the same run, so book equity during an entry pass reflects the PREVIOUS session's
 * close. That one-session lag is deliberate and is the whole reason this is not written earlier in
 * the run — moving the fetch ahead of the exit pass would re-sample the in-progress daily bar at a
 * different instant and could change an exit decision. A one-session-old close is wrong by one
 * session's move; the status quo it replaces is wrong by the entire holding period (up to +59% on a
 * 37-day hold, measured).
 *
 * <p><b>A miss is NOT fatal and NOT silent.</b> Callers fall back to {@code avgEntryPrice} exactly as
 * before — no NULL propagation into equity, no refused batch, no changed exit — but the position is
 * counted as unmarked and surfaced: {@code AccountDto.unmarkedPositions}, the
 * {@code ay_paper_mtm_blind_positions} gauge, and a WARN. Failing the ENTRY gate CLOSED on a miss was
 * considered and rejected: this cache is cold on every boot by construction, entries run before exits
 * within a run, and a fail-closed gate keyed on it would refuse EVERY manas entry on any restart day
 * — strictly worse than today and the exact opposite of what this change is for. See the PR body's
 * OPEN DOUBTS for the owner call.
 */
@Component
public class EquityMarkCache {

  /** A captured daily-bar close: the price, the session it closed, and when it was captured. */
  public record Mark(BigDecimal price, LocalDate session, Instant capturedAt) {}

  private final ConcurrentHashMap<String, Mark> marks = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration maxAge;

  /** Wires the (test-overridable) clock and the staleness bound. */
  public EquityMarkCache(
      Clock clock, @Value("${artha.paper.equity-mark.max-age-hours:96}") long maxAgeHours) {
    this.clock = clock;
    this.maxAge = Duration.ofHours(maxAgeHours);
  }

  /** The stable {@code EXCHANGE:TRADINGSYMBOL} key — the same grammar {@link LastTickReader} uses. */
  private static String key(String exchange, String tradingsymbol) {
    return exchange + ":" + tradingsymbol;
  }

  /**
   * Records a session's closing price for a symbol. LAST WRITE WINS — unlike
   * {@link ManasGoverningStopCache#put} this is deliberately NOT a ratchet: a mark is a market fact
   * that must be free to move DOWN, and a "never loosens" rule here would pin book equity to each
   * position's high-water mark and systematically overstate it. A null or non-positive price is
   * ignored (a zero close is not a real mark).
   */
  public void put(String exchange, String tradingsymbol, BigDecimal price, LocalDate session) {
    if (price == null || price.signum() <= 0) {
      return;
    }
    marks.put(key(exchange, tradingsymbol), new Mark(price, session, clock.instant()));
  }

  /** The cached mark if one was captured recently enough, else empty (never a stale price). */
  public Optional<BigDecimal> price(String exchange, String tradingsymbol) {
    Mark mark = marks.get(key(exchange, tradingsymbol));
    if (mark == null) {
      return Optional.empty();
    }
    return Duration.between(mark.capturedAt(), clock.instant()).compareTo(maxAge) > 0
        ? Optional.empty()
        : Optional.of(mark.price());
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
