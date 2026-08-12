package in.arthayantra.marketdata.futures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.NotFoundException;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.instruments.Instrument;
import in.arthayantra.marketdata.instruments.InstrumentRepository;
import in.arthayantra.marketdata.kite.InstrumentKey;
import in.arthayantra.marketdata.kite.QuoteGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the cross-service invariant <b>"a non-throwing {@code termStructure} always carries a
 * non-empty {@code contracts}"</b> — H8 / chip {@code task_f624fca7}.
 *
 * <p>Why this file exists at all: that invariant is load-bearing for the LIVE LOAD PATH of every
 * one of the 38 enabled scalpers, and until now it was written down only in a merged PR body
 * (#877's, "market-data never returns 200-with-empty-contracts") and a code comment. There was no
 * {@code FuturesTermStructureServiceTest}; the only endpoint coverage,
 * {@code FuturesSliceIntegrationTest:113-127}, asserts a healthy ladder has 3 legs and never
 * exercises a degradation. So the premise was true, unenforced, and consumed across a service
 * boundary — the exact shape that rots silently.
 *
 * <p>What breaks it is not exotic: making this read fail-soft instead of throwing is the pattern
 * the same service already uses one directory over ({@code FuturesDigestService} — "fail-soft to
 * null + a reason (never a 5xx)"). This suite fails the day someone does that here, which is the
 * whole point of writing it.
 *
 * <p>Evidence and reasoning: {@code docs/signal-analysis/2026-08-03-h8-empty-index-contracts.md}.
 */
class FuturesTermStructureServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  // A Tuesday, 12:20 IST — inside the session, so `stale` is false on the happy path.
  private static final Clock CLOCK =
      Clock.fixed(OffsetDateTime.of(2026, 8, 11, 12, 20, 0, 0, IST).toInstant(), IST);

  private static final InstrumentKey SPOT = new InstrumentKey("NSE", "NIFTY 50");
  private static final InstrumentKey AUG = new InstrumentKey("NFO", "NIFTY26AUGFUT");
  private static final InstrumentKey SEP = new InstrumentKey("NFO", "NIFTY26SEPFUT");

  @Test
  @DisplayName("INVARIANT: every non-throwing return carries a non-empty contracts list")
  void everyDegradationEitherThrowsOrReturnsANonEmptyLadder() {
    // The invariant stated as a sweep rather than as one happy-path assertion, because the risk is
    // not that the happy path breaks — it is that a NEW degradation path gets added that returns
    // rather than throws. Each case below is one of the four exits termStructure has today.
    record Case(String label, Supplier<FuturesTermStructureService> subject) {}

    List<Case> cases =
        List.of(
            new Case("healthy ladder", () -> service(ladder(), new StubQuotes(quotes(SPOT, AUG, SEP)))),
            new Case("no spot quote", () -> service(ladder(), new StubQuotes(quotes(AUG, SEP)))),
            new Case("no contract quotes", () -> service(ladder(), new StubQuotes(quotes(SPOT)))),
            new Case("quote gateway throws", () -> service(ladder(), failingGateway())),
            new Case("no listed futures", () -> service(List.of(), new StubQuotes(quotes(SPOT, AUG, SEP)))));

    for (Case c : cases) {
      FuturesTermStructureService svc = c.subject().get();
      FuturesTermStructureService.TermStructure result;
      try {
        result = svc.termStructure("NIFTY 50");
      } catch (RuntimeException expected) {
        // Throwing is a PASS. The consumer maps 404 and 503 to UNRESOLVED, which is counted,
        // retried and paged. It is the silent 200 that this invariant forbids.
        assertThat(expected)
            .as("%s: must fail loudly, not softly", c.label())
            .isInstanceOfAny(NotFoundException.class, ApiException.class);
        continue;
      }
      assertThat(result.contracts())
          .as("%s: returned 200 — then contracts MUST be non-empty", c.label())
          .isNotEmpty();
    }
  }

  @Test
  @DisplayName("an empty ladder is a 404, never a 200 with an empty list")
  void anEmptyLadderThrowsNotFoundRatherThanReturningAnEmptyList() {
    // The specific branch #877's premise rests on. An INDEX root always has listed futures, so no
    // active non-expired FUT row is a DATA FAULT (tombstoned NFO sync, drifted underlying
    // derivation, typo'd ref) — and the consumer can only treat it as one if it arrives as a 404.
    assertThatThrownBy(
            () -> service(List.of(), new StubQuotes(quotes(SPOT, AUG, SEP))).termStructure("NIFTY 50"))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("no FUT contracts");
  }

  @Test
  @DisplayName("expired rungs are filtered, and filtering everything out is still a 404")
  void aLadderOfOnlyExpiredContractsIsA404() {
    // The filter is `!expiry.isBefore(today)`, so a stale instruments table whose rungs have all
    // rolled past produces an empty POST-FILTER ladder from a non-empty query result. That is the
    // realistic way this branch is reached, and it must land on the same 404.
    List<Instrument> expired =
        List.of(future("NIFTY26JULFUT", LocalDate.of(2026, 7, 28)),
            future("NIFTY26JUNFUT", LocalDate.of(2026, 6, 30)));

    assertThatThrownBy(() -> service(expired, new StubQuotes(quotes(SPOT, AUG, SEP))).termStructure("NIFTY 50"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("the stale cache can never serve an empty ladder, because it can never hold one")
  void theStaleFallbackServesTheLastNonEmptyStructure() {
    // staleFallback is the ONE return path that does not build its own legs — it copies
    // `lastGood`. The reachability argument for the whole invariant depends on `lastGood` being
    // writable only downstream of the non-empty guard, which is a claim about ordering inside the
    // method and therefore worth pinning behaviourally rather than by inspection: warm the cache,
    // then degrade, and assert the served list is the warm one.
    StubQuotes gateway = new StubQuotes(quotes(SPOT, AUG, SEP));
    FuturesTermStructureService svc = service(ladder(), gateway);

    assertThat(svc.termStructure("NIFTY 50").contracts()).hasSize(2);

    gateway.fail = true;
    FuturesTermStructureService.TermStructure stale = svc.termStructure("NIFTY 50");
    assertThat(stale.stale()).isTrue();
    assertThat(stale.contracts())
        .as("a stale serve is still a non-empty serve")
        .hasSize(2);
  }

  @Test
  @DisplayName("a cold cache under the same degradation is a 503, not an empty 200")
  void aColdCacheDegradationIsA503() {
    // The control for the test above: same failure, no warm entry. This is the boundary where a
    // future "make it fail-soft" refactor would most plausibly return an empty structure instead.
    StubQuotes gateway = new StubQuotes(quotes(SPOT, AUG, SEP));
    gateway.fail = true;

    assertThatThrownBy(() -> service(ladder(), gateway).termStructure("NIFTY 50"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("no term structure available");
  }

  // ---------------------------------------------------------------------------------------------

  /** A quote gateway that answers from a fixed map, and can be flipped to throw. */
  private static final class StubQuotes implements QuoteGateway {
    private final Map<InstrumentKey, Quote> answers;
    private boolean fail;

    StubQuotes(Map<InstrumentKey, Quote> answers) {
      this.answers = answers;
    }

    @Override
    public Map<InstrumentKey, Quote> quotes(java.util.Collection<InstrumentKey> keys) {
      if (fail) {
        throw new IllegalStateException("no live Kite session");
      }
      Map<InstrumentKey, Quote> out = new LinkedHashMap<>();
      keys.stream().filter(answers::containsKey).forEach(k -> out.put(k, answers.get(k)));
      return out;
    }
  }

  private static FuturesTermStructureService service(
      List<Instrument> ladder, QuoteGateway gateway) {
    InstrumentRepository instruments = mock(InstrumentRepository.class);
    when(instruments.futures(anyString())).thenReturn(ladder);
    MarketCalendar calendar = mock(MarketCalendar.class);
    when(calendar.isTradingDay(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    return new FuturesTermStructureService(instruments, gateway, calendar, CLOCK);
  }

  private static QuoteGateway failingGateway() {
    StubQuotes g = new StubQuotes(Map.of());
    g.fail = true;
    return g;
  }

  private static List<Instrument> ladder() {
    return List.of(
        future("NIFTY26AUGFUT", LocalDate.of(2026, 8, 25)),
        future("NIFTY26SEPFUT", LocalDate.of(2026, 9, 29)));
  }

  private static Instrument future(String tradingsymbol, LocalDate expiry) {
    return new Instrument(
        "NFO", tradingsymbol, 1L, tradingsymbol, "NFO-FUT", "FUT", "NSE", "NIFTY 50", expiry,
        null, null, 75, true);
  }

  private static Map<InstrumentKey, QuoteGateway.Quote> quotes(InstrumentKey... present) {
    Map<InstrumentKey, QuoteGateway.Quote> all = new LinkedHashMap<>();
    for (InstrumentKey key : present) {
      all.put(key, quote(key));
    }
    return all;
  }

  private static QuoteGateway.Quote quote(InstrumentKey key) {
    return new QuoteGateway.Quote(
        key, new BigDecimal("24583.65"), null, null, 1000L, 2000L, null,
        OffsetDateTime.ofInstant(Instant.now(CLOCK), IST));
  }
}
